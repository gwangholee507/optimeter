// 서버가 준 최적화 remark를 화면에 보여주기 위한 순수 함수들 (화면 상태와 분리해서 테스트하기 위해 App.jsx에서 뺐다)

export const REMARK_KIND_LABEL = { APPLIED: '적용', MISSED: '놓침' }

/**
 * 적용된 것과 놓친 것이 각각 몇 건인지 센다.
 * 서버가 같은 위치의 같은 remark를 하나로 합쳐서 주므로, 합쳐진 것은 한 건으로 센다 (count는 더하지 않는다).
 */
export function summarizeRemarks(remarks) {
  const summary = { applied: 0, missed: 0 }
  for (const remark of remarks) {
    if (remark.kind === 'APPLIED') summary.applied += 1
    else summary.missed += 1
  }
  return summary
}

/**
 * remark 앞에 붙일 최적화 종류 이름. 서버가 아는 종류면 한국어 이름을, 모르면 컴파일러가 붙인 패스 이름을 쓴다.
 * GCC의 remark처럼 둘 다 없으면 null이고, 화면은 메시지만 보여준다.
 */
export function remarkTopic(remark) {
  return remark.topicLabel ?? remark.pass ?? null
}
