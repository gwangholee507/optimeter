# 프로젝트 규칙 (이 저장소에서 작업할 때 반드시 지킬 것)

이 파일은 이 저장소에서 Claude Code가 코드/문서를 다룰 때 따라야 하는 규칙입니다.
새 세션이라도 이 파일을 읽었다면 아래 규칙을 항상 적용합니다.

## Git 브랜치 전략

전체 규칙: [docs/branching-strategy.md](docs/branching-strategy.md) — **GitHub Flow**

핵심만 요약:
- `main`에 직접 커밋/푸시하지 않습니다. 항상 브랜치를 만들어 작업합니다.
- 브랜치 이름: `feature/*`, `fix/*`, `docs/*`, `chore/*` 중 하나로 시작.
- 작업이 끝나면 PR을 올리고, CI 통과 후 **Squash merge**로 `main`에 합칩니다.
- 머지 후 브랜치는 삭제합니다. 원격 브랜치는 GitHub가 자동으로 지우고, 로컬 브랜치는 직접 지웁니다.

## 커밋 메시지 규칙

전체 규칙: [docs/commit-convention.md](docs/commit-convention.md) — **Conventional Commits**

```
<type>(<scope>): <설명>
```

`feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `ci`, `perf`, `style` 중 하나를 type으로 사용.
scope는 선택(`backend`, `frontend`, `docs` 등). 커밋 메시지 예시는 문서 참고.

## PR 작성

- PR을 올릴 땐 [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) 형식을 채워서 작성합니다
  (무엇을/왜 바꿨는지, 테스트 방법, 체크리스트).
- 머지는 사용자 승인 후 진행합니다 — Claude가 임의로 `main`에 병합하지 않습니다.

## 문서 동기화

- 브랜치/커밋 전략을 바꾸게 되면 이 파일과 `docs/branching-strategy.md`,
  `docs/commit-convention.md`를 함께 업데이트합니다 (내용이 서로 어긋나지 않도록).
- 함수/모듈 동작이 바뀌면 관련 `docs/*.md`도 같이 업데이트합니다
  ([docs/README.md](docs/README.md) 참고 — 이 문서들은 자동 생성되지 않습니다).
