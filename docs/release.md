# 릴리스 방법

사용자에게 배포하는 형태는 **프론트엔드까지 들어 있는 jar 파일 하나**입니다.
버전 태그를 올리면 GitHub Actions([release.yml](../.github/workflows/release.yml))가 jar를 만들어
[Releases](https://github.com/gwangholee507/optimeter/releases) 페이지에 자동으로 올립니다.

## 릴리스 올리기

`main`이 배포할 상태인지(CI 통과) 확인한 뒤, `main`의 최신 커밋에 `v`로 시작하는 태그를 붙여 올립니다.

```bash
git switch main
git pull
git tag v0.1.0
git push origin v0.1.0
```

- 태그는 `v1.2.3` 또는 `v1.2.3-rc.1` 형식이어야 합니다 (다른 형식이면 워크플로가 실패합니다).
- 태그에서 `v`를 뗀 값이 버전이 됩니다. `v0.1.0` → `optimeter-0.1.0.jar`
- 버전 번호는 [유의적 버전](https://semver.org/lang/ko/)을 따릅니다: 호환되지 않는 변경은 앞자리,
  기능 추가는 가운데, 버그 수정은 끝자리를 올립니다.

## 워크플로가 하는 일

1. JDK 17 설치, clang/gcc 설치
2. `./mvnw -Pbundle -Drevision=<버전> package` — 테스트를 통과해야 jar가 만들어짐
3. 만든 jar를 실제로 띄워보는 스모크 테스트 ([smoke-test.sh](../.github/scripts/smoke-test.sh))
4. jar를 워크플로 아티팩트로 업로드
5. (태그로 실행된 경우에만) GitHub Release를 만들고 jar를 첨부. 릴리스 노트는 지난 릴리스 이후 머지된 PR 제목으로 자동 생성됨

## 태그를 올리기 전에 미리 확인해보기

GitHub의 **Actions → Release → Run workflow**로 수동 실행하면 1~4번만 수행합니다. Release는 만들지 않고,
만들어진 jar는 실행 결과 페이지의 Artifacts에서 내려받을 수 있습니다.

## 잘못 올렸을 때

Release와 태그를 지우고 다시 올리면 됩니다.

```bash
gh release delete v0.1.0 --cleanup-tag
```

이미 누군가 내려받았을 수 있으므로, 같은 버전 번호를 다시 쓰기보다는 다음 버전(`v0.1.1`)으로 고쳐 올리는 편이 안전합니다.
