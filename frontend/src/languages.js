// 언어 선택과 언어별 코드 초안을 다루는 순수 함수들 (화면 상태와 분리해서 테스트하기 위해 App.jsx에서 뺐다)

// 언어를 처음 고르거나 "예제로 되돌리기"를 누르면 들어가는 예제 코드.
// C/C++은 표준 헤더를 쓰지 않는 예제만 둔다 — Windows에 LLVM만 설치한 환경에서는 표준 헤더를 찾지 못해 첫 화면부터 실패하기 때문.
export const SAMPLE_CODE = {
  c: `int square(int x) {
    return x * x;
}

int sum_loop(int n) {
    int total = 0;
    for (int i = 0; i < n; i++) {
        total += square(i);
    }
    return total;
}
`,
  cpp: `template <typename T>
T square(T x) {
    return x * x;
}

struct Accumulator {
    int total = 0;
    void add(int x) { total += square(x); }
};

int sum_loop(int n) {
    Accumulator acc;
    for (int i = 0; i < n; i++) {
        acc.add(i);
    }
    return acc.total;
}
`,
  rust: `fn square(x: i32) -> i32 {
    x * x
}

// fn main이 없으면 라이브러리로 컴파일되어 pub 함수만 어셈블리에 남습니다.
pub fn sum_loop(n: i32) -> i32 {
    (0..n).map(square).sum()
}
`,
}

export const INITIAL_LANGUAGE = 'c'

// 서버에서 컴파일러 목록을 받지 못했을 때 보여줄 목록. 서버는 기본 컴파일러로 clang을 쓰므로 그것만 둔다.
export const FALLBACK_COMPILERS = [
  { id: 'clang', label: 'Clang (LLVM)', available: true, language: 'c', languageLabel: 'C' },
]

/**
 * 컴파일러 목록을 언어별로 묶는다. 언어 순서와 언어 안의 컴파일러 순서는 서버가 준 순서를 그대로 따른다.
 * 언어의 available은 그 언어에 사용 가능한 컴파일러가 하나라도 있는지를 뜻한다.
 */
export function groupByLanguage(compilers) {
  const groups = []
  for (const compiler of compilers) {
    let group = groups.find((g) => g.id === compiler.language)
    if (!group) {
      group = { id: compiler.language, label: compiler.languageLabel, available: false, compilers: [] }
      groups.push(group)
    }
    group.compilers.push(compiler)
    group.available = group.available || compiler.available
  }
  return groups
}

/** 그 언어에서 기본으로 고를 컴파일러: 사용 가능한 첫 컴파일러, 하나도 없으면 첫 컴파일러. */
export function defaultCompilerFor(language) {
  return language.compilers.find((c) => c.available) ?? language.compilers[0]
}

/** 페이지를 열었을 때 고를 언어: 사용 가능한 컴파일러가 있는 첫 언어, 하나도 없으면 첫 언어. */
export function initialLanguage(languages) {
  return languages.find((l) => l.available) ?? languages[0]
}

/**
 * 언어를 바꿀 때 쓴다. 지금 편집 중인 코드를 원래 언어의 초안으로 보관하고,
 * 새 언어의 초안(없으면 그 언어의 예제 코드)을 에디터에 넣을 코드로 돌려준다.
 */
export function switchDraft(drafts, fromLanguage, toLanguage, currentCode) {
  const nextDrafts = { ...drafts, [fromLanguage]: currentCode }
  return { drafts: nextDrafts, code: nextDrafts[toLanguage] ?? SAMPLE_CODE[toLanguage] ?? '' }
}
