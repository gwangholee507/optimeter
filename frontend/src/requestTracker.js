// 가장 나중에 보낸 요청의 응답만 화면에 넣게 해 준다.
// start()가 돌려준 isCurrent()는 그 뒤에 start()나 invalidate()가 다시 불리면 false가 된다.
// 응답이 도착했을 때 isCurrent()가 false면 그 응답은 이미 쓸모가 없으니 버리면 된다.
export function createRequestTracker() {
  let latest = 0
  return {
    start() {
      const id = ++latest
      return () => latest === id
    },
    // 진행 중인 요청을 모두 버린다 (요청 자체를 취소하지는 않는다)
    invalidate() {
      latest++
    },
  }
}
