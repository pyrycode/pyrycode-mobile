/** Capture-only host coordinator; the existing manager owns admission. */
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync, openSync, closeSync, fsyncSync, renameSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';

type Receipt = { run_id: string; subtree_complete: boolean; export_complete: boolean; exit: number };
type Transport = { code: number | null; signal: string | null };
type Manager = { run: (pending: any, work: () => Promise<boolean>) => Promise<any> };

export async function admittedCapture(manager: Manager, pending: any, runId: string,
  launch: () => Promise<Transport>, readReceipt: () => Receipt | undefined,
  requestCancel: () => void, pollMs = 1000): Promise<number> {
  let exit = 125;
  await manager.run(pending, async () => {
    let closed = false;
    let transportFailed = false;
    const transport = launch().then(result => {
      closed = true;
      transportFailed = result.code !== 0;
    }, () => { closed = true; transportFailed = true; });
    for (;;) {
      let receipt: Receipt | undefined;
      try { receipt = readReceipt(); } catch { /* Unknown: retain reservation. */ }
      if (receipt?.run_id === runId && receipt.subtree_complete === true && Number.isInteger(receipt.exit)) {
        await transport;
        exit = receipt.exit || (receipt.export_complete ? 0 : 125);
        return exit === 0;
      }
      if (closed && transportFailed) {
        try { requestCancel(); } catch { /* Unknown: retain reservation. */ }
      }
      await sleep(pollMs);
    }
  });
  return exit;
}

function durable(path: string, record: unknown) {
  const temporary = path + '.pending';
  writeFileSync(temporary, JSON.stringify(record, null, 2) + '\n', { mode: 0o600 });
  const fd = openSync(temporary, 'r');
  try { fsyncSync(fd); } finally { closeSync(fd); }
  renameSync(temporary, path);
  const directory = openSync(dirname(path), 'r');
  try { fsyncSync(directory); } finally { closeSync(directory); }
}

async function main(config: any) {
  // Config contains only paths, identities and bounds. Credentials remain inherited.
  const { ManagedDispatch, trackManagedChild } = await import(pathToFileURL(config.managerModule).href);
  const root = config.hostRoot;
  mkdirSync(root, { recursive: false, mode: 0o700 });
  const state: any = { run_id: config.runId, started: new Date().toISOString(),
    maximumWallClockSeconds: config.maximumSeconds,
    maximumAdmissionWaitSeconds: config.admissionWaitSeconds, state: 'waiting' };
  const save = () => durable(root + '/admission.json', state);
  const cancel = () => writeFileSync(config.hostCaptureRoot + '/cancel', 'cancel\n', { mode: 0o600 });
  let cancelled = false;
  for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => {
    cancelled = true;
    try { cancel(); } catch { /* Supervisor may not have started yet. */ }
  });
  const manager = new ManagedDispatch('pyrycode/releases', { ...process.env,
    PYRY_MANAGER_URL: config.managerUrl, PYRY_MANAGER_TOKEN: process.env[config.tokenVariable] });
  save();
  await manager.start();
  try {
    const pending = manager.offer(config.offerId, 'maintenance-emulator-diagnostic', 'heavy',
      ['pyrycode/releases:release', 'pyrycode/releases:emulator-diagnostic']);
    const deadline = Date.now() + config.admissionWaitSeconds * 1000;
    await manager.sync();
    while (!manager.ready(pending)) {
      if (cancelled || Date.now() >= deadline) throw new Error('Admission cancelled or expired; zero tests executed');
      console.log('HP capture: waiting for ordinary heavy-manager admission');
      await sleep(10000);
      await manager.sync();
    }
    state.grant = pending.run;
    state.state = 'admitted';
    state.admittedAt = new Date().toISOString();
    save();
    process.exitCode = await admittedCapture(manager, pending, config.runId, () => new Promise(resolve => {
      const child = spawn('podman', ['exec', config.container, 'python3', config.supervisor, config.containerConfig],
        { detached: true, stdio: 'inherit' });
      if (child.pid) trackManagedChild(child.pid);
      child.once('error', () => resolve({ code: 125, signal: null }));
      child.once('close', (code, signal) => resolve({ code, signal }));
    }), () => {
      if (cancelled) { try { cancel(); } catch {} }
      try { return JSON.parse(readFileSync(config.hostCaptureRoot + '/complete.json', 'utf8')); }
      catch { return undefined; }
    }, cancel);
    state.state = 'completed';
    state.exit = process.exitCode;
    state.finished = new Date().toISOString();
    save();
  } catch (error) {
    state.state = 'error';
    state.error = error instanceof Error ? error.message : 'Coordinator failure';
    save();
    process.exitCode = 125;
  } finally {
    await manager.stop();
    state.reservationFinished = new Date().toISOString();
    save();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await main(JSON.parse(readFileSync(process.argv[2], 'utf8')));
}
