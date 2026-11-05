import { AlertTriangle, CheckCircle2, CircleDashed, Database, Inbox, Loader2, PauseCircle, Search, Webhook, XCircle, type LucideIcon } from "lucide-react";
import { useId } from "react";
import type { JobView } from "@/api/client";
import { useJob } from "@/api/queries";
import { Card, CardHeader } from "@/components/ui/layout";
import { cn, formatNumber, formatRelative } from "@/lib/format";
import { useConnectionNames } from "./actions";
import { sinkTarget } from "./jobForm";
import { sinkState, type SinkView } from "./SinksPanel";

type Tone = "good" | "warning" | "critical" | "info" | "neutral";
/** How data moves along an edge: animated while it moves, dashed where it is held back. */
type Flow = "flowing" | "retrying" | "waiting" | "held" | "broken" | "idle";

const toneFill: Record<Tone, string> = {
  good: "fill-good",
  warning: "fill-warning",
  critical: "fill-critical",
  info: "fill-primary",
  neutral: "fill-border",
};
const toneText: Record<Tone, string> = {
  good: "fill-good-text",
  warning: "fill-warning-text",
  critical: "fill-critical-text",
  info: "fill-primary",
  neutral: "fill-muted-foreground",
};
const flowStroke: Record<Flow, string> = {
  flowing: "stroke-primary",
  retrying: "stroke-warning",
  waiting: "stroke-primary",
  held: "stroke-warning",
  broken: "stroke-critical",
  idle: "stroke-border",
};

interface Status {
  icon: LucideIcon;
  label: string;
  tone: Tone;
  spin?: boolean;
}

// Layout in viewBox units; the SVG scales to the card width (with a minimum so text stays legible).
const NODE_H = 104;
const ROW = 124;
const COL = { source: 0, detect: 240, outbox: 480, sink: 790 };
const W = { source: 200, detect: 200, outbox: 180, sink: 210 };
const WIDTH = COL.sink + W.sink;

/** While data is moving, refresh the job every 2 s so the diagram follows it; otherwise the usual 30 s. */
function useLiveJob(job: JobView) {
  const moving = job.running || job.sinks.some((s) => s.delivering || (s.backlog > 0 && s.status === "ACTIVE"));
  return useJob(job.id, moving ? 2_000 : 30_000).data ?? job;
}

/**
 * The job's pipeline as a diagram (design 6): one reader detects changes and writes them to the outbox, and each sink
 * drains the outbox on its own. Edges animate where data is moving, and every node shows its status as an icon and a label.
 */
export function JobFlowDiagram({ job: initial }: { job: JobView }) {
  const job = useLiveJob(initial);
  const connectionName = useConnectionNames();
  const markerId = useId().replace(/:/g, "");
  const c = job.config;
  const last = job.lastRun;
  const sinks = job.sinks.map((view, i) => ({ view, def: c.sinks[i] }));
  const height = Math.max(sinks.length, 1) * ROW - (ROW - NODE_H);
  const upstreamY = (height - NODE_H) / 2;
  const mid = upstreamY + NODE_H / 2;
  const totalBacklog = job.sinks.reduce((n, s) => n + s.backlog, 0);
  const reading: Flow = job.running ? "flowing" : "idle";
  const writing: Flow = job.running ? "flowing" : job.status === "NEEDS_REVIEW" ? "held" : "idle";

  const sourceStatus: Status = job.running
    ? { icon: Loader2, label: "Reading", tone: "info", spin: true }
    : job.status === "PAUSED"
      ? { icon: PauseCircle, label: "Job paused", tone: "neutral" }
      : { icon: CircleDashed, label: job.nextRunAt ? `Next read ${formatRelative(job.nextRunAt)}` : "Idle", tone: "neutral" };
  const detectStatus: Status = job.running
    ? { icon: Loader2, label: last?.kind === "RECONCILE" ? "Reconciling" : "Comparing", tone: "info", spin: true }
    : job.status === "NEEDS_REVIEW"
      ? { icon: AlertTriangle, label: "Deletes held", tone: "warning" }
      : last?.state === "FAILED"
        ? { icon: XCircle, label: "Last run failed", tone: "critical" }
        : last
          ? { icon: CheckCircle2, label: `${formatNumber(last.upserts)} upserts, ${formatNumber(last.deletes)} deletes`, tone: "good" }
          : { icon: CircleDashed, label: "Never run", tone: "neutral" };
  const outboxStatus: Status =
    totalBacklog > 0
      ? { icon: Inbox, label: `${formatNumber(totalBacklog)} batch${totalBacklog === 1 ? "" : "es"} queued`, tone: "info" }
      : { icon: CheckCircle2, label: "Empty", tone: "good" };

  const summary = [
    `${connectionName(c.source.connection)} ${c.source.object}: ${sourceStatus.label}`,
    `Change detection: ${detectStatus.label}`,
    `Outbox: ${outboxStatus.label}`,
    ...sinks.map(({ view }) => `${view.label}: ${sinkState(view).label}, ${view.backlog} waiting`),
  ].join(". ");

  return (
    <Card>
      <CardHeader
        title="Data flow"
        description="One reader detects changes and queues them in the outbox; each sink takes them from the queue on its own."
      />
      <div className="overflow-x-auto p-5">
        <svg
          viewBox={`-2 -2 ${WIDTH + 4} ${height + 4}`}
          className="h-auto w-full min-w-[680px]"
          role="img"
          aria-label={summary}
        >
          <defs>
            {(Object.keys(flowStroke) as Flow[]).map((f) => (
              <marker key={f} id={`${markerId}-${f}`} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M0 1 L9 5 L0 9 z" className={flowStroke[f].replace("stroke-", "fill-")} />
              </marker>
            ))}
          </defs>

          <Edge from={[COL.source + W.source, mid]} to={[COL.detect, mid]} flow={reading} marker={markerId} />
          <Edge from={[COL.detect + W.detect, mid]} to={[COL.outbox, mid]} flow={writing} marker={markerId} />
          {sinks.map(({ view }, i) => {
            const flow = sinkFlow(view);
            const y = i * ROW + NODE_H / 2;
            return (
              <g key={view.id}>
                <Edge from={[COL.outbox + W.outbox, mid]} to={[COL.sink, y]} flow={flow} marker={markerId} />
                {view.backlog > 0 && (
                  <text x={COL.sink - 10} y={y - 8} textAnchor="end" className="fill-muted-foreground text-[11px] tabular-nums">
                    {formatNumber(view.backlog)} waiting
                  </text>
                )}
              </g>
            );
          })}

          <Node
            x={COL.source}
            y={upstreamY}
            w={W.source}
            icon={Database}
            kicker="Source"
            title={connectionName(c.source.connection)}
            detail={c.source.object}
            status={sourceStatus}
          />
          <Node
            x={COL.detect}
            y={upstreamY}
            w={W.detect}
            icon={Search}
            kicker="Change detection"
            title={c.mode === "SNAPSHOT_DIFF" ? "Snapshot diff" : c.mode === "HYBRID" ? "Hybrid" : "Watermark"}
            detail={c.mode === "SNAPSHOT_DIFF" ? "hash index" : job.watermarkValue ? `at ${job.watermarkValue}` : "no watermark yet"}
            status={detectStatus}
          />
          <Node x={COL.outbox} y={upstreamY} w={W.outbox} icon={Inbox} kicker="Outbox" title="Change queue" detail={`${sinks.length} recipient${sinks.length === 1 ? "" : "s"}`} status={outboxStatus} />
          {sinks.map(({ view, def }, i) => {
            const s = sinkState(view);
            const webhook = def?.type === "webhook";
            return (
              <Node
                key={view.id}
                x={COL.sink}
                y={i * ROW}
                w={W.sink}
                icon={webhook ? Webhook : Database}
                kicker={webhook ? "Webhook sink" : "Table sink"}
                title={view.label}
                detail={def ? `${connectionName(def.connection)} · ${sinkTarget(def)}` : undefined}
                status={{ ...s, label: view.attempts > 0 && view.nextRetryAt && s.label === "Retrying" ? `Retrying ${formatRelative(view.nextRetryAt)}` : s.label }}
                note={view.lastError ?? undefined}
              />
            );
          })}
        </svg>
      </div>
      <Legend />
    </Card>
  );
}

function sinkFlow(sink: SinkView): Flow {
  if (sink.status === "RESNAPSHOT_REQUIRED") return "broken";
  if (sink.status === "PAUSED") return "held";
  if (sink.delivering) return "flowing";
  if (sink.attempts > 0) return "retrying";
  return sink.backlog > 0 ? "waiting" : "idle";
}

function Edge({ from: [x1, y1], to: [x2, y2], flow, marker }: { from: [number, number]; to: [number, number]; flow: Flow; marker: string }) {
  const mx = (x1 + x2) / 2;
  const d = `M${x1} ${y1} C${mx} ${y1} ${mx} ${y2} ${x2 - 2} ${y2}`;
  const moving = flow === "flowing" || flow === "retrying";
  return (
    <g>
      <path
        d={d}
        fill="none"
        strokeWidth={2}
        className={cn(flowStroke[flow], moving && "opacity-40")}
        strokeDasharray={dashed(flow) ? "5 5" : undefined}
        markerEnd={`url(#${marker}-${flow})`}
      />
      {moving && (
        <path d={d} fill="none" strokeWidth={6} strokeLinecap="round" className={cn("flow-dots", flow === "retrying" && "flow-dots-slow", flowStroke[flow])} />
      )}
    </g>
  );
}

function Node({
  x,
  y,
  w,
  icon: Icon,
  kicker,
  title,
  detail,
  status,
  note,
}: {
  x: number;
  y: number;
  w: number;
  icon: LucideIcon;
  kicker: string;
  title: string;
  detail?: string;
  status: Status;
  note?: string;
}) {
  const StatusIcon = status.icon;
  const chars = Math.floor((w - 28) / 7.2);
  return (
    <g transform={`translate(${x} ${y})`}>
      <title>{[kicker, title, detail, status.label, note].filter(Boolean).join("\n")}</title>
      <rect width={w} height={NODE_H} rx={8} strokeWidth={1} className="fill-surface stroke-border" />
      <rect x={0} y={10} width={4} height={NODE_H - 20} rx={2} className={toneFill[status.tone]} />
      <Icon x={14} y={11} width={13} height={13} className="text-subtle-foreground" aria-hidden />
      <text x={32} y={22} className="fill-subtle-foreground text-[11px] font-medium tracking-wide uppercase">
        {truncate(kicker, chars - 2)}
      </text>
      <text x={14} y={45} className="fill-foreground text-[14px] font-medium">
        {truncate(title, chars - 2)}
      </text>
      {detail && (
        <text x={14} y={63} className="fill-muted-foreground font-mono text-[11px]">
          {truncate(detail, chars)}
        </text>
      )}
      <g transform={`translate(14 ${NODE_H - 26})`}>
        {/* a <g> spins about its own box; nested <svg> elements do not take CSS transforms reliably */}
        <g className={cn(status.spin && "animate-spin [transform-box:fill-box] origin-center")}>
          <StatusIcon width={14} height={14} className={toneText[status.tone].replace("fill-", "text-")} aria-hidden />
        </g>
        <text x={20} y={11} className={cn("text-[12px] font-medium", toneText[status.tone])}>
          {truncate(status.label, chars - 3)}
        </text>
      </g>
    </g>
  );
}

function Legend() {
  const items: [Flow, string][] = [
    ["flowing", "Data moving"],
    ["retrying", "Retrying after a failure"],
    ["waiting", "Changes waiting"],
    ["held", "Held: paused or awaiting review"],
    ["broken", "Needs resnapshot"],
    ["idle", "Idle"],
  ];
  return (
    <ul className="flex flex-wrap gap-x-5 gap-y-2 border-t px-5 py-3 text-xs text-muted-foreground">
      {items.map(([flow, label]) => (
        <li key={flow} className="flex items-center gap-2">
          <svg width="28" height="8" aria-hidden>
            <line
              x1="1"
              y1="4"
              x2="27"
              y2="4"
              strokeWidth={flow === "flowing" || flow === "retrying" ? 4 : 2}
              strokeLinecap="round"
              strokeDasharray={dashed(flow) ? "4 4" : flow === "idle" ? undefined : "0.1 8"}
              className={flowStroke[flow]}
            />
          </svg>
          {label}
        </li>
      ))}
    </ul>
  );
}

function dashed(flow: Flow) {
  return flow === "waiting" || flow === "held" || flow === "broken";
}

function truncate(text: string, max: number) {
  return text.length > max ? `${text.slice(0, Math.max(max - 1, 1))}…` : text;
}
