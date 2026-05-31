from __future__ import annotations

import base64
import hashlib
import os

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


class KeyCipher:
    def __init__(self, master_key: str | None):
        if not master_key:
            raise RuntimeError("BACKEND_MASTER_KEY is required to store OpenAI API keys.")
        self._key = self._normalize_key(master_key)

    @staticmethod
    def _normalize_key(master_key: str) -> bytes:
        try:
            decoded = base64.b64decode(master_key, validate=True)
            if len(decoded) == 32:
                return decoded
        except Exception:
            pass
        return hashlib.sha256(master_key.encode("utf-8")).digest()

    def encrypt(self, value: str) -> str:
        nonce = os.urandom(12)
        ciphertext = AESGCM(self._key).encrypt(nonce, value.encode("utf-8"), None)
        return base64.b64encode(nonce + ciphertext).decode("ascii")

    def decrypt(self, encoded: str) -> str:
        payload = base64.b64decode(encoded)
        nonce = payload[:12]
        ciphertext = payload[12:]
        return AESGCM(self._key).decrypt(nonce, ciphertext, None).decode("utf-8")

