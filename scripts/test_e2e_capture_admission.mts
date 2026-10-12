import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, existsSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';
import { admittedCapture, reapHostObserver, startHostObservers } from './e2e-capture-admission.mts';

test('container receipt, not podman exit, orders reservation completion', async () => {
  const events: string[] = [];
  let receipt: any;
  const manager = { run: async (_: any, work: any) => {
    events.push('held'); const success = await work(); events.push('released'); return success;
  }};
  const code = await admittedCapture(manager, {}, 'identity', async () => {
    setTimeout(() => { events.push('last-write-and-subtree-exit'); receipt = {
      run_id: 'identity', subtree_complete: true, export_complete: true, exit: 7,
    }; }, 50);
    return { code: 1, signal: null };
  }, () => receipt, () => { events.push('cancel-request'); }, 5,
  async () => { events.push('host-observers-reaped'); });
  assert.equal(code, 7);
  assert.ok(events.indexOf('released') > events.indexOf('last-write-and-subtree-exit'));
  assert.ok(events.indexOf('released') > events.indexOf('host-observers-reaped'));
});

test('wrong run and incomplete receipt cannot release admission', async () => {
  let reads = 0;
  const manager = { run: async (_: any, work: any) => work() };
  const code = await admittedCapture(manager, {}, 'current', async () => ({ code: 0, signal: null }),
    () => { reads++; return { run_id: reads < 3 ? 'old' : 'current',
      subtree_complete: reads >= 5, export_complete: true, exit: 0 }; }, () => {}, 1);
  assert.ok(reads >= 5);
  assert.equal(code, 0);
});

test('export failure propagates without replacing failed gate status', async () => {
  const manager = { run: async (_: any, work: any) => work() };
  for (const exit of [0, 9]) {
    const code = await admittedCapture(manager, {}, 'current', async () => ({ code: 0, signal: null }),
      () => ({ run_id: 'current', subtree_complete: true, export_complete: false, exit }), () => {}, 1);
    assert.equal(code, exit || 125);
  }
});

test('host observer ignoring TERM is killed and reaped before completion', async () => {
  const root = mkdtempSync(join(tmpdir(), 'capture-host-observer-test-'));
  const ready = join(root, 'ready');
  const child = spawn('python3', ['-c', `import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); open(${JSON.stringify(ready)},'w').write('ready'); time.sleep(10)`],
    { detached: true, stdio: 'ignore' });
  const closed = new Promise<void>(resolve => child.once('close', () => resolve()));
  for (let i = 0; i < 200 && !existsSync(ready); i++) await sleep(5);
  assert.ok(existsSync(ready));
  await reapHostObserver(child, closed, 50);
  assert.equal(child.signalCode, 'SIGKILL');
  assert.throws(() => process.kill(-child.pid!, 0), /ESRCH/);
});

test('host collector startup failure is unknown while gate status survives', async () => {
  const root = mkdtempSync(join(tmpdir(), 'capture-host-startup-test-'));
  writeFileSync(join(root, 'host-observer-0-private.log'), 'preserved');
  const observers = startHostObservers([['missing-observer'], ['missing-observer']], root, () => {});
  await Promise.all(observers.map(item => item.closed));
  assert.ok(observers.every(item => item.record.state === 'startup-unknown'));
  const manager = { run: async (_: any, work: any) => work() };
  const code = await admittedCapture(manager, {}, 'current', async () => ({ code: 7, signal: null }),
    () => ({ run_id: 'current', subtree_complete: true, export_complete: true, exit: 7 }), () => {}, 1);
  assert.equal(code, 7);
  assert.equal(readFileSync(join(root, 'host-observer-0-private.log'), 'utf8'), 'preserved');
});

test('EAGAIN cannot release fake reservation before detached last write', {
  skip: process.platform !== 'linux',
}, async () => {
  const root = mkdtempSync(join(tmpdir(), 'capture-admission-test-'));
  const held = join(root, 'held');
  const marker = join(root, 'last-write');
  const evidence = join(root, 'evidence');
  const modulePath = join(dirname(fileURLToPath(import.meta.url)), 'e2e-capture-supervisor.py');
  const descendant = `import time,os; time.sleep(.25); assert os.path.exists(${JSON.stringify(held)}); open(${JSON.stringify(marker)},'w').write('finished')`;
  const gate = `import subprocess,sys,time; subprocess.Popen([sys.executable,'-c',${JSON.stringify(descendant)}],start_new_session=True); time.sleep(.1)`;
  const config = { root: evidence, run_id: 'integration', maximum_seconds: 3,
    sample_seconds: .02, grace_seconds: .1, command: ['python3', '-c', gate] };
  const python = `import importlib.util,errno,json; s=importlib.util.spec_from_file_location('capture',${JSON.stringify(modulePath)}); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)
def exhausted(): raise OSError(errno.EAGAIN, 'injected adb spawn failure')
raise SystemExit(m.capture(json.loads(${JSON.stringify(JSON.stringify(config))}),observe=exhausted))`;
  const manager = { run: async (_: any, work: any) => {
    writeFileSync(held, 'held');
    const result = await work();
    assert.ok(existsSync(marker));
    unlinkSync(held);
    return result;
  }};
  const code = await admittedCapture(manager, {}, 'integration', () => new Promise(resolve => {
    const child = spawn('python3', ['-c', python], { stdio: 'inherit' });
    child.on('close', (code, signal) => resolve({ code, signal }));
  }), () => {
    try { return JSON.parse(readFileSync(join(evidence, 'complete.json'), 'utf8')); }
    catch { return undefined; }
  }, () => writeFileSync(join(evidence, 'cancel'), 'cancel'), 5);
  assert.equal(code, 0);
  const outcome = JSON.parse(readFileSync(join(evidence, 'outcome.json'), 'utf8'));
  assert.ok(outcome.errors.includes('observation:11'));
  assert.ok(outcome.reaped.length >= 2);
  await sleep(10);
});
