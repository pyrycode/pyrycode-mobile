/** Capture-only host coordinator; the existing manager owns admission. */
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync, openSync, closeSync, fsyncSync, renameSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';

type Receipt = { run_id: string; subtree_complete: boolean; export_complete: boolean; exit: number };
type Transport = { code: number | null; signal: string | null };
type Manager = { run: (pending: any, work: () => Promise<boolean>) => Promise<any> };

export function startHostObservers(commands: string[][], root: string, track: (pid: number) => void) {
  return commands.map((command, index) => {
    const record: any = { started: new Date().toISOString() };
    try {
      const fd = openSync(root + `/host-observer-${index}-private.log`, 'wx', 0o600);
      let observer;
      try { observer = spawn(command[0], command.slice(1), { detached: true, stdio: ['ignore', fd, fd] }); }
      finally { closeSync(fd); }
      record.pid = observer.pid;
      if (observer.pid) track(observer.pid);
      const closed = new Promise<void>(resolve => {
        observer.once('error', () => { record.state = 'startup-unknown'; resolve(); });
        observer.once('close', (code, signal) => { record.code = code; record.signal = signal; resolve(); });
      });
      return { observer, closed, record };
    } catch {
      record.state = 'startup-unknown';
      return { observer: undefined, closed: Promise.resolve(), record };
    }
  });
}

export async function reapHostObserver(observer: any, closed: Promise<void>, graceMs = 5000) {
  const groupAlive = () => {
    if (!observer.pid) return false;
    try { process.kill(-observer.pid, 0); return true; }
    catch (error) { if ((error as NodeJS.ErrnoException).code === 'ESRCH') return false; throw error; }
  };
  const signalGroup = (signal: NodeJS.Signals) => {
    if (groupAlive()) {
      try { process.kill(-observer.pid, signal); }
      catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error; }
    }
  };
  signalGroup('SIGTERM');
  const deadline = Date.now() + graceMs;
  while (groupAlive() && Date.now() < deadline) await sleep(20);
  signalGroup('SIGKILL');
  await closed;
  while (groupAlive()) await sleep(20);
}

export async function admittedCapture(manager: Manager, pending: any, runId: string,
  launch: () => Promise<Transport>, readReceipt: () => Receipt | undefined,
  requestCancel: () => void, pollMs = 1000,
  onTerminal: () => Promise<void> = async () => {}): Promise<number> {
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
        await onTerminal();
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
  const observers: any[] = [];
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
      observers.push(...startHostObservers(config.hostObservers ?? [], root, trackManagedChild));
      const child = spawn('podman', ['exec', config.container, 'python3', config.supervisor, config.containerConfig],
        { detached: true, stdio: 'inherit' });
      if (child.pid) trackManagedChild(child.pid);
      child.once('error', () => resolve({ code: 125, signal: null }));
      child.once('close', (code, signal) => resolve({ code, signal }));
    }), () => {
      if (cancelled) { try { cancel(); } catch {} }
      try { return JSON.parse(readFileSync(config.hostCaptureRoot + '/complete.json', 'utf8')); }
      catch { return undefined; }
    }, cancel, 1000, async () => {
      for (const { observer, closed } of observers) {
        if (observer) await reapHostObserver(observer, closed);
      }
      durable(root + '/host-observers.json', observers.map(item => item.record));
    });
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
