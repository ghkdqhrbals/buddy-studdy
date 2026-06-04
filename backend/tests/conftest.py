import pytest

from app.storage.repository import Database


@pytest.fixture
def db(tmp_path):
    path = str(tmp_path / "buddystuddy.db")
    instance = Database(path=path)
    instance.init()
    return instance

