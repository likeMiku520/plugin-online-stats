<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

export interface Point {
  date: string
  views: number
  visitors: number
  cumulative?: number
}

const props = withDefaults(
  defineProps<{
    series: Point[]
    metric?: 'views' | 'visitors' | 'cumulative'
    height?: number
    showBars?: boolean
  }>(),
  { metric: 'views', height: 240, showBars: true },
)

const hovered = ref<number | null>(null)
const svgRef = ref<SVGSVGElement | null>(null)

const metric = computed(() => props.metric)
const values = computed(() => props.series.map((p) => Number(p[metric.value] ?? 0)))
const maxValue = computed(() => Math.max(1, ...values.value))
const hasData = computed(() => props.series.length > 0 && values.value.some((v) => v > 0))

/* The viewBox tracks the rendered pixel size so nothing is stretched; the SVG scales
   1:1 and only the drawing coordinates change. */
const width = ref(880)
const padLeft = 46
const padRight = 14
const padTop = 14
const padBottom = 30

const innerWidth = computed(() => Math.max(10, width.value - padLeft - padRight))
const innerHeight = computed(() => Math.max(10, props.height - padTop - padBottom))
const step = computed(() =>
  props.series.length <= 1 ? innerWidth.value : innerWidth.value / (props.series.length - 1),
)

function x(index: number): number {
  return padLeft + step.value * index
}

function y(value: number): number {
  return padTop + innerHeight.value - (value / maxValue.value) * innerHeight.value
}

const linePath = computed(() => {
  if (!props.series.length) {
    return ''
  }
  return values.value
    .map((value, index) => `${index === 0 ? 'M' : 'L'}${x(index).toFixed(1)},${y(value).toFixed(1)}`)
    .join(' ')
})

const areaPath = computed(() => {
  if (!props.series.length) {
    return ''
  }
  const baseline = (padTop + innerHeight.value).toFixed(1)
  return `${linePath.value} L${x(props.series.length - 1).toFixed(1)},${baseline} L${padLeft},${baseline} Z`
})

const barWidth = computed(() =>
  Math.max(2, Math.min(18, innerWidth.value / Math.max(props.series.length, 1) - 3)),
)

const ticks = computed(() => {
  const count = 4
  const result: { value: number; y: number }[] = []
  for (let i = 0; i <= count; i++) {
    const value = Math.round((maxValue.value / count) * i)
    result.push({ value, y: y(value) })
  }
  return result
})

const labelIndexes = computed(() => {
  const total = props.series.length
  if (total <= 1) {
    return [0]
  }
  const wanted = Math.min(6, total)
  const gap = Math.max(1, Math.floor((total - 1) / (wanted - 1 || 1)))
  const result: number[] = []
  for (let i = 0; i < total; i += gap) {
    result.push(i)
  }
  if (result[result.length - 1] !== total - 1) {
    result.push(total - 1)
  }
  return result
})

const hoveredPoint = computed(() =>
  hovered.value === null ? null : props.series[hovered.value] ?? null,
)

function shortDate(date: string): string {
  return date.length >= 10 ? date.slice(5) : date
}

function measure() {
  const node = svgRef.value
  if (node) {
    const rect = node.getBoundingClientRect()
    if (rect.width > 0) {
      width.value = rect.width
    }
  }
}

function onMove(event: MouseEvent) {
  measure()
  const node = svgRef.value
  if (!node) {
    return
  }
  const rect = node.getBoundingClientRect()
  const ratio = (event.clientX - rect.left) / rect.width
  const index = Math.round((ratio * width.value - padLeft) / step.value)
  hovered.value = Math.max(0, Math.min(props.series.length - 1, index))
}

let observer: ResizeObserver | undefined

onMounted(() => {
  measure()
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => measure())
    if (svgRef.value) {
      observer.observe(svgRef.value)
    }
  }
})

onUnmounted(() => {
  observer?.disconnect()
})
</script>

<template>
  <div class="os-chart">
    <div v-if="hoveredPoint" class="os-chart__tip">
      <strong>{{ hoveredPoint.date }}</strong>
      <span>浏览 {{ hoveredPoint.views }}</span>
      <span>访客 {{ hoveredPoint.visitors }}</span>
      <span v-if="hoveredPoint.cumulative !== undefined">累计 {{ hoveredPoint.cumulative }}</span>
    </div>

    <svg
      ref="svgRef"
      class="os-chart__svg"
      :viewBox="`0 0 ${width} ${height}`"
      :style="{ height: `${height}px` }"
      @mousemove="onMove"
      @mouseleave="hovered = null"
    >
      <defs>
        <linearGradient id="osAreaFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#39C5BB" stop-opacity="0.28" />
          <stop offset="100%" stop-color="#39C5BB" stop-opacity="0" />
        </linearGradient>
      </defs>

      <g class="os-chart__grid">
        <line
          v-for="tick in ticks"
          :key="`g${tick.value}`"
          :x1="padLeft"
          :x2="width - padRight"
          :y1="tick.y"
          :y2="tick.y"
        />
      </g>

      <g class="os-chart__axis">
        <text v-for="tick in ticks" :key="`t${tick.value}`" :x="padLeft - 8" :y="tick.y + 4">
          {{ tick.value }}
        </text>
      </g>

      <template v-if="hasData">
        <g v-if="showBars" class="os-chart__bars">
          <rect
            v-for="(value, index) in values"
            :key="`b${index}`"
            :x="x(index) - barWidth / 2"
            :y="y(value)"
            :width="barWidth"
            :height="Math.max(0, padTop + innerHeight - y(value))"
            rx="3"
          />
        </g>
        <path class="os-chart__area" :d="areaPath" />
        <path class="os-chart__line" :d="linePath" />
        <line
          v-if="hovered !== null"
          class="os-chart__cursor"
          :x1="x(hovered)"
          :x2="x(hovered)"
          :y1="padTop"
          :y2="padTop + innerHeight"
        />
        <circle
          v-if="hovered !== null"
          class="os-chart__dot"
          :cx="x(hovered)"
          :cy="y(values[hovered] ?? 0)"
          r="4.5"
        />
      </template>
      <text v-else class="os-chart__empty" :x="width / 2" :y="height / 2">
        最近这段时间还没有访问数据
      </text>

      <g class="os-chart__axis os-chart__axis--x">
        <text v-for="index in labelIndexes" :key="`x${index}`" :x="x(index)" :y="height - 8">
          {{ shortDate(series[index]?.date || '') }}
        </text>
      </g>
    </svg>
  </div>
</template>

<style lang="scss" scoped>
.os-chart {
  position: relative;
  width: 100%;

  &__svg {
    display: block;
    width: 100%;
  }

  &__grid line {
    stroke: #f1f5f9;
    stroke-width: 1;
  }

  &__axis text {
    font-size: 10px;
    fill: #9ca3af;
    text-anchor: end;
  }

  &__axis--x text {
    text-anchor: middle;
  }

  &__bars rect {
    fill: #d8f4f1;
  }

  &__area {
    fill: url(#osAreaFill);
  }

  &__line {
    fill: none;
    stroke: #39c5bb;
    stroke-width: 2;
    stroke-linejoin: round;
    stroke-linecap: round;
  }

  &__cursor {
    stroke: #b9e7e3;
    stroke-width: 1;
    stroke-dasharray: 3 3;
  }

  &__dot {
    fill: #fff;
    stroke: #39c5bb;
    stroke-width: 2;
  }

  &__empty {
    font-size: 12px;
    fill: #cbd5e1;
    text-anchor: middle;
  }

  &__tip {
    position: absolute;
    top: 0;
    left: 0.5rem;
    z-index: 2;
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    padding: 0.25rem 0.5rem;
    font-size: 0.6875rem;
    color: #334155;
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 0.375rem;

    strong {
      color: #0f172a;
    }
  }
}
</style>