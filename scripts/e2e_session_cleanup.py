"""Close sessions created by one test, through launcher-owned control sockets."""
import json
import socket


def control_request(path, body):
    with socket.socket(socket.AF_UNIX) as client:
        client.settimeout(15)
        client.connect(str(path))
        client.sendall(json.dumps(body).encode() + b'\n')
        reply = json.loads(client.makefile('rb').readline(1024 * 1024))
    if reply.get('error'):
        raise RuntimeError('owned session control refused')
    return reply


class TestSessions:
    def __init__(self, sockets, request=control_request):
        self.sockets = sockets
        self.request = request
        self.baseline = None

    def rows(self, path):
        reply = self.request(path, {'verb': 'sessions.list'})
        return reply['sessionsList']['sessions'] or []

    def begin(self):
        if self.baseline is not None:
            raise RuntimeError('previous test session cleanup did not complete')
        self.baseline = {path: {row['id'] for row in self.rows(path)} for path in self.sockets()}

    def end(self):
        if self.baseline is None:
            raise RuntimeError('test session ownership was not recorded')
        failures = []
        removed = 0
        for path in self.sockets():
            try:
                baseline = self.baseline.get(path, set())
                owned = [row['id'] for row in self.rows(path)
                         if row['id'] not in baseline and not row.get('bootstrap', False)]
                for sid in owned:
                    try:
                        # Omit jsonlPolicy: the control protocol preserves transcripts by default.
                        reply = self.request(path, {'verb': 'sessions.rm', 'sessions': {'id': sid}})
                        if reply.get('ok') is not True:
                            raise RuntimeError('owned session removal was not acknowledged')
                        removed += 1
                    except (OSError, ValueError, RuntimeError, KeyError) as error:
                        failures.append(error)
                remaining = {row['id'] for row in self.rows(path)
                             if row['id'] not in baseline and not row.get('bootstrap', False)}
                if remaining:
                    failures.append(RuntimeError('test sessions remain after cleanup'))
            except (OSError, ValueError, RuntimeError, KeyError) as error:
                failures.append(error)
        if failures:
            # Retain ownership after failure: the next test must not adopt leftovers as its baseline.
            raise RuntimeError('test session cleanup failed') from failures[0]
        self.baseline = None
        return removed
