# SPDX-FileCopyrightText: MetaCognition India Pvt Limited
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import http.cookiejar
import json
import urllib.error
import urllib.request


class HStoreError(Exception):
    def __init__(self, message, code=None, retryable=False):
        super().__init__(message)
        self.code = code
        self.retryable = retryable


def quote(text):
    return "'" + str(text).replace("'", "''") + "'"


class HStore:
    def __init__(self, url="http://127.0.0.1:7480", user=None, password=None, timeout=120):
        self.url = url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        if user:
            self.login(user, password or "")

    def _call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.url + path, data=data, method=method,
                                         headers={"Content-Type": "application/json", "X-HStore-Studio": "1"})
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                return json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as failure:
            detail = failure.read().decode("utf-8", errors="replace")
            try:
                detail = json.loads(detail).get("error", detail)
            except ValueError:
                pass
            raise HStoreError(f"HTTP {failure.code}: {detail}", code=failure.code) from None

    def info(self):
        return self._call("GET", "/api/info")

    def login(self, user, password):
        return self._call("POST", "/api/login", {"user": user, "password": password})

    def logout(self):
        return self._call("POST", "/api/logout", {})

    def execute(self, script):
        frames = self._call("POST", "/api/query", {"script": script}).get("results", [])
        for frame in frames:
            error = frame.get("error")
            if isinstance(error, dict) and isinstance(error.get("error"), dict):
                error = error["error"]
            if error:
                raise HStoreError(f"{error.get('code')}: {error.get('message')} (in: {frame.get('text', '').strip()[:200]})",
                                  code=error.get("code"), retryable=bool(error.get("retryable")))
        return frames

    def rows(self, statement):
        frames = self.execute(statement)
        if not frames:
            return []
        last = frames[-1]
        columns = last.get("columns", [])
        return [dict(zip(columns, row)) for row in last.get("rows", [])]

    def message(self, statement):
        frames = self.execute(statement)
        return frames[-1].get("message", "") if frames else ""
