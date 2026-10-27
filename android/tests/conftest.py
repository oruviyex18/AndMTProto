import logging

import pytest


@pytest.fixture(scope="session", autouse=True)
def _detach_background_logging():
    """Keep upstream's daemon threads from logging into a closed stderr.

    Every proxy start launches upstream's CF domain refresh thread, which keeps
    running and logging. After the session pytest closes its captured stderr;
    a thread writing there during interpreter shutdown aborts Python (exit 134).
    """
    yield
    from proxy import config
    config._refresh_stop.set()  # the current refresh thread; older ones were stopped by their successors
    root = logging.getLogger()
    for handler in list(root.handlers):
        if type(handler) is logging.StreamHandler:  # the adapter's console handler, not the log file
            root.removeHandler(handler)
    root.addHandler(logging.NullHandler())  # also keeps logging's last-resort stderr handler away
