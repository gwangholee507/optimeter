// diffLines를 화면 스레드 밖에서 돌리는 worker. 어셈블리가 수천 줄이면 비교에 수백 ms가 걸려서
// 화면 스레드에서 하면 그동안 화면이 멈춘다 (diffRunner.js가 비교마다 하나씩 띄운다).
import { diffLines } from './diff'

self.onmessage = ({ data }) => {
  self.postMessage(diffLines(data.base.split('\n'), data.target.split('\n')))
}
