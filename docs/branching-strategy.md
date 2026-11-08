# 브랜치 전략 (GitHub Flow)

이 프로젝트는 **GitHub Flow**를 사용합니다. `main` 브랜치 하나만 영구적으로 유지하고,
나머지는 전부 짧게 살다 사라지는 작업 브랜치입니다. Git Flow처럼 `develop`/`release`
브랜치를 따로 두지 않습니다 — 릴리스를 여러 버전 동시에 유지보수할 필요가 없는
소규모 프로젝트라 오히려 단순한 쪽이 낫다고 판단했습니다.

## 규칙

1. **`main`은 항상 배포 가능한 상태를 유지합니다.**
   - `main`에 직접 커밋하지 않습니다. 모든 변경은 브랜치 → PR → 머지로 들어갑니다.
   - CI([`.github/workflows/ci.yml`](../.github/workflows/ci.yml))가 통과해야 머지합니다.

2. **작업 브랜치는 `main`에서 분기합니다.**
   - 이름 규칙: `<종류>/<짧은-설명>`
     - `feature/xxx` — 새 기능
     - `fix/xxx` — 버그 수정
     - `docs/xxx` — 문서만 변경
     - `chore/xxx` — 빌드/설정/의존성 등 잡무
   - 예: `feature/asm-diff-view`, `fix/mockito-jdk-mismatch`

3. **작업이 끝나면 PR을 올립니다.**
   - PR에는 무엇을 왜 바꿨는지 간단히 씁니다.
   - CI 통과 + (협업 시) 리뷰 승인 후 `main`으로 머지합니다.
   - 머지 방식은 **Squash merge**를 기본으로 합니다 (커밋 히스토리를 깔끔하게 유지).

4. **머지 후 브랜치는 삭제합니다.**
   - 원격 브랜치는 PR이 머지되면 GitHub가 자동으로 삭제합니다
     (아래 [저장소 설정](#머지-후-브랜치-자동-삭제-github-settings) 참고). "Delete branch" 버튼을 따로 누르지 않아도 됩니다.
   - 로컬 브랜치는 자동으로 지워지지 않으므로 직접 지웁니다.
     ```
     git switch main
     git pull --prune
     git branch -D <브랜치 이름>
     ```
     Squash merge는 브랜치의 커밋을 새 커밋 하나로 합쳐 넣기 때문에, Git은 원래 브랜치가 머지됐다고 알아보지 못합니다.
     그래서 `-d`로는 지워지지 않고 `-D`가 필요합니다. 지우기 전에 PR이 머지됐는지 꼭 확인하세요.
     `--prune`은 GitHub에서 지워진 원격 브랜치의 추적 정보(`origin/<브랜치 이름>`)도 함께 정리합니다.

5. **머지되면 곧 배포 가능한 상태입니다.**
   - 지금은 수동 배포/로컬 실행 단계지만, 나중에 자동 배포를 붙이더라도
     "`main`에 머지 = 배포 대상"이라는 전제가 바뀌지 않도록 설계합니다.

## 긴급 수정(hotfix)이 필요할 때

별도의 `hotfix/*` 규칙을 두지 않습니다. `fix/*` 브랜치로 동일하게 처리하되,
급한 경우 리뷰를 생략하고 CI만 통과하면 바로 머지할 수 있습니다.

## 브랜치 보호 규칙 (GitHub Settings)

위 규칙(특히 "`main` 직접 커밋 금지", "CI 통과 후 머지")을 실제로 강제하려면
GitHub 저장소 설정에서 브랜치 보호 규칙을 켜야 합니다. 아래는 이 프로젝트에
권장하는 설정이며, 저장소 관리자가 GitHub 웹 UI에서 직접 설정합니다.

**Settings → Branches → Add branch protection rule** (`main` 대상)

- ☑ Require a pull request before merging
- ☑ Require status checks to pass before merging → CI 워크플로 선택
- ☑ Require branches to be up to date before merging
- (협업 인원이 늘면) ☑ Require approvals — 최소 1명

## 머지 후 브랜치 자동 삭제 (GitHub Settings)

"머지 후 브랜치 삭제" 규칙 중 원격 브랜치 삭제는 저장소 설정으로 자동화되어 있습니다 (켜져 있음).

**Settings → General → Pull Requests**

- ☑ Automatically delete head branches

이 설정은 원격 브랜치만 지웁니다. 로컬 브랜치는 위 규칙 4처럼 직접 지웁니다.
삭제된 브랜치는 PR 화면의 "Restore branch" 버튼으로 되살릴 수 있습니다.

## 요약 흐름

```
main ──●───────●───────●───────●──→ (항상 배포 가능)
        \     / \     / \     /
     feature/a  fix/b  docs/c
```

브랜치 생성 → 커밋 → PR → CI 통과 → 머지 → 브랜치 삭제. 이 사이클을 반복합니다.
