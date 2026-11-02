# 문서 (Docs)

이 폴더는 **Optimeter**(컴파일러 최적화 옵션 비교기) 프로젝트의 함수/모듈 단위 설명서입니다.
코드를 처음부터 다시 읽지 않고도 "이 함수가 뭘 하는지"를 빠르게 파악하기 위한 용도입니다.

## 구성

| 문서 | 내용 |
|---|---|
| [user-guide.md](./user-guide.md) | **사용자 가이드** — 실행 방법, 화면 사용법, 결과 읽는 법, FAQ (코드 몰라도 됨) |
| [architecture.md](./architecture.md) | 전체 흐름도 — 요청이 프론트엔드에서 백엔드까지 어떻게 흘러가는지 |
| [optimization-levels.md](./optimization-levels.md) | 컴파일러 최적화 레벨(O0~O3, Rust의 opt-level) 차이점 정리 |
| [differentiation.md](./differentiation.md) | 비슷한 도구(Compiler Explorer 등)와 비교한 차별점, 향후 발전 방향 |
| [backend.md](./backend.md) | Spring Boot 백엔드의 클래스/함수별 설명 |
| [frontend.md](./frontend.md) | React 프론트엔드의 컴포넌트/함수별 설명 |
| [branching-strategy.md](./branching-strategy.md) | Git 브랜치 전략 (GitHub Flow) — 브랜치 이름 규칙, PR/머지 규칙 |
| [commit-convention.md](./commit-convention.md) | 커밋 메시지 규칙 (Conventional Commits) |
| [release.md](./release.md) | 릴리스 방법 — 버전 태그를 올리면 실행 가능한 jar가 자동으로 배포됨 |
| [brand/](./brand/) | 로고 원본 — 앱 아이콘, 가로 로고(어두운/밝은 배경). 화면 헤더용 사본은 `frontend/src/assets/`, 파비콘은 `frontend/public/`에 있음 |

## 코드가 바뀌면?

이 문서는 자동 생성되지 않습니다. 함수 시그니처나 동작이 바뀌면 해당 문서도 같이 업데이트해주세요.
(작은 프로젝트라 지금은 수동 관리가 오히려 간단합니다.)
