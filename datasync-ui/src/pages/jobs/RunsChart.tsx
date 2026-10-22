import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis, type TooltipContentProps } from "recharts";
import type { NameType, ValueType } from "recharts/types/component/DefaultTooltipContent";
import type { RunView } from "@/api/client";
import { formatDateTime, formatNumber } from "@/lib/format";

const SERIES = [
  { key: "upserts", label: "Upserts", color: "var(--series-1)" },
  { key: "deletes", label: "Deletes", color: "var(--series-2)" },
] as const;

type Point = { at: string; label: string; upserts: number; deletes: number; state: string };

function RunTooltip({ active, payload }: TooltipContentProps<ValueType, NameType>) {
  const point = payload?.[0]?.payload as Point | undefined;
  if (!active || !point) return null;
  return (
    <div className="rounded-md border border-border bg-surface px-3 py-2 text-xs shadow-md">
      <p className="mb-1 font-medium">{formatDateTime(point.at)}</p>
      {SERIES.map((s) => (
        <p key={s.key} className="flex items-center gap-2">
          <span className="size-2 rounded-sm" style={{ background: s.color }} aria-hidden />
          {s.label}
          <span className="ml-auto pl-4 tabular-nums">{formatNumber(point[s.key])}</span>
        </p>
      ))}
      <p className="mt-1 text-muted-foreground">{point.state.toLowerCase().replace("_", " ")}</p>
    </div>
  );
}

/** Rows changed per run, oldest to newest. The Runs tab is the table view of the same data. */
export default function RunsChart({ runs }: { runs: RunView[] }) {
  const data: Point[] = runs
    .filter((r) => r.state !== "RUNNING")
    .slice(0, 30)
    .reverse()
    .map((r) => ({
      at: r.startedAt,
      label: new Date(r.startedAt).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }),
      upserts: r.upserts,
      deletes: r.deletes,
      state: r.state,
    }));
  if (data.length === 0) return <p className="py-10 text-center text-sm text-muted-foreground">No finished runs yet.</p>;

  return (
    <div className="h-64" role="img" aria-label={`Rows changed in the last ${data.length} runs`}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis dataKey="label" tick={{ fontSize: 11, fill: "var(--subtle-foreground)" }} tickLine={false} axisLine={{ stroke: "var(--border)" }} minTickGap={24} />
          <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: "var(--subtle-foreground)" }} tickLine={false} axisLine={false} width={48} tickFormatter={formatNumber} />
          <Tooltip content={RunTooltip} cursor={{ fill: "var(--muted)" }} />
          <Legend
            iconType="square"
            iconSize={10}
            wrapperStyle={{ fontSize: 12 }}
            formatter={(label) => <span style={{ color: "var(--muted-foreground)" }}>{label}</span>}
          />
          {SERIES.map((s, i) => (
            <Bar
              key={s.key}
              dataKey={s.key}
              name={s.label}
              stackId="changes"
              fill={s.color}
              maxBarSize={24}
              stroke="var(--surface)"
              strokeWidth={2}
              radius={i === SERIES.length - 1 ? [4, 4, 0, 0] : 0}
              isAnimationActive={false}
            />
          ))}
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
