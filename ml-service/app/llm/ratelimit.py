import time
import threading

class TokenBucket:
    def __init__(self, rpm: int):
        self.rpm = max(1, rpm)
        self.interval = 60.0 / self.rpm
        self.last_time = 0.0
        self.lock = threading.Lock()

    def acquire(self):
        with self.lock:
            now = time.time()
            elapsed = now - self.last_time
            if elapsed < self.interval:
                time.sleep(self.interval - elapsed)
            self.last_time = time.time()
