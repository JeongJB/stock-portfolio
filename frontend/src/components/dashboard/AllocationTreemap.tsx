import { useEffect, useMemo, useRef, useState } from 'react'
import {
  hierarchy,
  treemap as d3treemap,
  treemapSquarify,
  type HierarchyRectangularNode,
} from 'd3-hierarchy'
import type { PortfolioView } from '../../api/types'
import { useCurrency } from '../../app/currencyContext'
import { formatMoney, formatPercent } from '../../app/format'

interface Props {
  data: PortfolioView
}

// 종목 leaf 노드: 종목 1개 = 사각형 1개.
interface Leaf {
  ticker: string
  weight: number // 0~1 (portfolio 응답 그대로 — 전체 자산 대비 비중, 현금 포함)
  marketValue: string | null
  dailyChangePct: number | null // % 단위. null 이면 회색.
  isCash: boolean
}

// 한 sector 의 묶음. children = sector 안의 종목들.
interface Branch {
  sector: string
  children: Leaf[]
}

interface TreeRoot {
  name: 'root'
  children: Branch[]
}

// d3-hierarchy 의 노드 data 는 합집합 — type guard 로 분기.
type NodeData = TreeRoot | Branch | Leaf

const SECTOR_UNCLASSIFIED = '분류 미지정'
const CASH_SECTOR = '현금'
const CASH_TICKER = 'USD 현금'
// d3 treemap 의 paddingTop — sector cell 의 상단에 sector 이름을 그릴 공간.
const SECTOR_HEADER_HEIGHT = 20
const CELL_INNER_PADDING = 1
// 컨테이너 기본 높이 (Tailwind h-72 와 동기). 초기 render 시 size 측정 전 fallback.
const DEFAULT_HEIGHT = 288

export function AllocationTreemap({ data }: Props) {
  const { currency } = useCurrency()
  const containerRef = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState<{ width: number; height: number }>({
    width: 0,
    height: DEFAULT_HEIGHT,
  })
  const [hover, setHover] = useState<{
    leaf: Leaf
    sector: string
    x: number
    y: number
  } | null>(null)

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const ro = new ResizeObserver((entries) => {
      const e = entries[0]
      if (!e) return
      // setState 는 ResizeObserver 콜백 (외부 reactive source) 안이라 effect 직접 호출 아님.
      setSize({ width: e.contentRect.width, height: e.contentRect.height })
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const tree = useMemo(() => buildTree(data, currency), [data, currency])

  const layout: HierarchyRectangularNode<NodeData> | null = useMemo(() => {
    if (size.width <= 0 || size.height <= 0) return null
    if (tree.children.length === 0) return null
    const root = hierarchy<NodeData>(tree, (node) =>
      isLeaf(node) ? undefined : (node as Branch | TreeRoot).children,
    )
      .sum((node) => (isLeaf(node) ? node.weight : 0))
      .sort((a, b) => (b.value ?? 0) - (a.value ?? 0))
    return d3treemap<NodeData>()
      .tile(treemapSquarify)
      .size([size.width, size.height])
      .paddingOuter(0)
      .paddingTop(SECTOR_HEADER_HEIGHT)
      .paddingInner(CELL_INNER_PADDING)
      .round(true)(root)
  }, [tree, size])

  if (tree.children.length === 0) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
        <h3 className="text-sm font-medium text-slate-700 dark:text-slate-200">자산 비중</h3>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          표시할 비중 데이터가 없습니다. (시세 조회가 모두 실패했거나 보유 자산이 없습니다.)
        </p>
      </section>
    )
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <h3 className="text-sm font-medium text-slate-700 dark:text-slate-200">자산 비중</h3>
      <div ref={containerRef} className="relative mt-2 h-72 w-full">
        {layout && size.width > 0 && (
          <svg width={size.width} height={size.height}>
            {/* sector 영역: 외곽 + 상단 헤더(이름) */}
            {layout.children?.map((sectorNode, idx) => {
              const branch = sectorNode.data as Branch
              const w = sectorNode.x1 - sectorNode.x0
              const h = sectorNode.y1 - sectorNode.y0
              if (w <= 0 || h <= 0) return null
              return (
                <g key={`sector-${idx}`}>
                  {/* 헤더 배경 */}
                  <rect
                    x={sectorNode.x0}
                    y={sectorNode.y0}
                    width={w}
                    height={SECTOR_HEADER_HEIGHT}
                    className="fill-slate-100 dark:fill-slate-800"
                  />
                  {/* 헤더 텍스트 */}
                  <text
                    x={sectorNode.x0 + 6}
                    y={sectorNode.y0 + SECTOR_HEADER_HEIGHT / 2 + 1}
                    dominantBaseline="middle"
                    fontSize={11}
                    fontWeight={600}
                    className="fill-slate-600 dark:fill-slate-300"
                    style={{ pointerEvents: 'none' }}
                  >
                    {branch.sector}
                  </text>
                  {/* sector 외곽 */}
                  <rect
                    x={sectorNode.x0}
                    y={sectorNode.y0}
                    width={w}
                    height={h}
                    fill="none"
                    className="stroke-slate-300 dark:stroke-slate-700"
                    strokeWidth={1}
                  />
                </g>
              )
            })}
            {/* 종목 leaf: 등락률 색상 + 영역 충분하면 ticker + 등락률 텍스트 */}
            {layout.leaves().map((node, i) => {
              const leaf = node.data as Leaf
              const sectorBranch = node.parent?.data as Branch | undefined
              const sector = sectorBranch?.sector ?? ''
              const w = node.x1 - node.x0
              const h = node.y1 - node.y0
              if (w <= 0 || h <= 0) return null
              const fill = pctColor(leaf.dailyChangePct, leaf.isCash)
              const tickerFontSize = pickTickerFontSize(w, h)
              const pctFontSize = pickPctFontSize(w, h)
              const showTicker = tickerFontSize > 0
              const showPct = pctFontSize > 0 && leaf.dailyChangePct != null
              const tickerY = showPct
                ? node.y0 + h / 2 - pctFontSize / 2 - 1
                : node.y0 + h / 2
              const pctY = node.y0 + h / 2 + tickerFontSize / 2 + 2
              return (
                <g
                  key={`leaf-${i}`}
                  onMouseEnter={(e) => {
                    const cont = containerRef.current?.getBoundingClientRect()
                    if (!cont) return
                    const tgt = (e.currentTarget as SVGGElement).getBoundingClientRect()
                    setHover({
                      leaf,
                      sector,
                      x: tgt.left + tgt.width / 2 - cont.left,
                      y: tgt.top - cont.top,
                    })
                  }}
                  onMouseLeave={() => setHover(null)}
                >
                  <rect x={node.x0} y={node.y0} width={w} height={h} fill={fill} />
                  {showTicker && (
                    <text
                      x={node.x0 + w / 2}
                      y={tickerY}
                      textAnchor="middle"
                      dominantBaseline="middle"
                      fontSize={tickerFontSize}
                      fontWeight={500}
                      fill="#fff"
                      style={{ pointerEvents: 'none' }}
                    >
                      {leaf.ticker}
                    </text>
                  )}
                  {showPct && (
                    <text
                      x={node.x0 + w / 2}
                      y={pctY}
                      textAnchor="middle"
                      dominantBaseline="middle"
                      fontSize={pctFontSize}
                      fill="#fff"
                      opacity={0.9}
                      style={{ pointerEvents: 'none' }}
                    >
                      {formatChangePct(leaf.dailyChangePct)}
                    </text>
                  )}
                </g>
              )
            })}
          </svg>
        )}
        {hover && (
          <div
            className="pointer-events-none absolute z-10 -translate-x-1/2 -translate-y-full rounded border border-slate-200 bg-white px-2.5 py-1.5 text-xs shadow-md dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            style={{ left: hover.x, top: hover.y - 4 }}
          >
            <div className="font-medium">{hover.leaf.ticker}</div>
            <div className="text-slate-700 dark:text-slate-200">
              {formatPercent(String(hover.leaf.weight))} ·{' '}
              {formatMoney(hover.leaf.marketValue, currency)}
            </div>
          </div>
        )}
      </div>
    </section>
  )
}

function isLeaf(node: NodeData): node is Leaf {
  return (node as Leaf).ticker != null
}

function buildTree(data: PortfolioView, currency: 'USD' | 'KRW'): TreeRoot {
  const grouped = new Map<string, Leaf[]>()
  for (const p of data.positions) {
    if (p.weight == null) continue
    const w = Number(p.weight)
    if (!Number.isFinite(w) || w <= 0) continue
    const sector =
      typeof p.sector === 'string' && p.sector.trim().length > 0
        ? p.sector
        : SECTOR_UNCLASSIFIED
    const leaves = grouped.get(sector) ?? []
    leaves.push({
      ticker: p.ticker,
      weight: w,
      marketValue: currency === 'USD' ? p.marketValueUsd : p.marketValueKrw,
      dailyChangePct: p.dailyChangePct != null ? Number(p.dailyChangePct) : null,
      isCash: false,
    })
    grouped.set(sector, leaves)
  }

  // sector 별 합계 비중으로 정렬 (큰 sector 가 좌상단). 알파벳 정렬보다 시각적으로 자연스러움.
  const sectorBranches: Branch[] = Array.from(grouped.entries())
    .map(([sector, children]) => ({ sector, children }))
    .sort((a, b) => sumWeight(b.children) - sumWeight(a.children))

  // 현금은 별도 "현금" sector 로 묶임.
  const cashWeight = Number(data.cashWeight)
  if (Number.isFinite(cashWeight) && cashWeight > 0) {
    sectorBranches.push({
      sector: CASH_SECTOR,
      children: [
        {
          ticker: CASH_TICKER,
          weight: cashWeight,
          marketValue: currency === 'USD' ? data.cashUsd : data.cashKrw,
          dailyChangePct: null,
          isCash: true,
        },
      ],
    })
  }

  return { name: 'root', children: sectorBranches }
}

function sumWeight(leaves: Leaf[]): number {
  return leaves.reduce((s, l) => s + l.weight, 0)
}

/**
 * 등락률 → 색상. 한국식: + 빨강, - 초록. 절댓값이 클수록 진해짐.
 * |pct| ≥ 5% 부터 max intensity. 현금/등락률 없음 → 회색 톤.
 */
function pctColor(pct: number | null, isCash: boolean): string {
  if (isCash) return '#94a3b8' // slate-400 — 현금은 평가 변동 없음
  if (pct == null || !Number.isFinite(pct) || pct === 0) {
    return '#cbd5e1' // slate-300 — 등락률 없음 / 보합
  }
  const abs = Math.min(Math.abs(pct), 5)
  // lightness: 78% (pct≈0) → 38% (pct≥5)
  const lightness = 78 - (abs / 5) * 40
  // saturation: 55% → 80%
  const hue = pct > 0 ? 0 : 140 // 0=red, 140=green
  return `hsl(${hue}, 100%, ${lightness}%)`
}

function formatChangePct(pct: number | null): string {
  if (pct == null || !Number.isFinite(pct)) return ''
  const sign = pct > 0 ? '+' : ''
  return `${sign}${pct.toFixed(2)}%`
}

// 셀이 좁아질수록 단계적으로 폰트 축소. 0 이면 라벨 숨김.
function pickTickerFontSize(w: number, h: number): number {
  if (w >= 70 && h >= 24) return 12
  if (w >= 50 && h >= 20) return 11
  if (w >= 32 && h >= 16) return 9
  return 0
}

function pickPctFontSize(w: number, h: number): number {
  if (w >= 80 && h >= 44) return 10
  if (w >= 60 && h >= 36) return 9
  return 0
}
