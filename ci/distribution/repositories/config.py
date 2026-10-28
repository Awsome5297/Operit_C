"""源码与发行仓库的固定分工。"""
from __future__ import annotations

SOURCE_REPOSITORY = "Awsome5297/Operit_C"
UPDATE_REPOSITORY = "Awsome5297/Operit_C-updates"
RESOURCE_REPOSITORY = "Awsome5297/Operit_C-resources"


def release_repositories(version: str) -> tuple[str, ...]:
    return (UPDATE_REPOSITORY,) if "+" in version else (SOURCE_REPOSITORY, UPDATE_REPOSITORY)


def source_info(commit: str, tag: str) -> dict:
    return {
        "sourceRepository": SOURCE_REPOSITORY,
        "sourceCommit": commit,
        "sourceTag": tag,
        "sourceUrl": f"https://github.com/{SOURCE_REPOSITORY}/tree/{commit}",
        "sourceArchiveUrl": f"https://github.com/{SOURCE_REPOSITORY}/archive/{commit}.zip",
    }
