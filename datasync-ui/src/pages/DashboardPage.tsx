import { AlertTriangle, Play, XCircle } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { useAccess, useJobs, useOverview } from "@/api/queries";
import { ErrorAlert } from "@/components/ErrorAlert";
import { JobStatusBadge, RunStateBadge } from "@/components/StatusBadge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, Empty, PageHeader, Spinner, Table, Td, Th } from "@/components/ui/layout";
import { cn, formatDuration, formatNumber, formatRelative } from "@/lib/format";
import { useJobActions } from "./jobs/actions";

function Tile({ label, value, note, tone, to }: { label: string; value: ReactNode; note?: ReactNode; tone?: "warning" | "critical"; to?: string }) {
  const card = (
    <Card className={cn("h-full p-4", to && "transition-colors hover:border-primary/50 hover:bg-muted/40")}>
      <p className="text-sm text-muted-foreground">{label}</p>
      <p
        className={cn(
          "mt-1 flex items-center gap-2 text-2xl font-semibold tabular-nums",
          tone === "warning" && "text-warning-text",
          tone === "critical" && "text-critical-text",
        )}
      >
        {tone === "warning" && <AlertTriangle className="size-5" aria-hidden />}
        {tone === "critical" && <XCircle className="size-5" aria-hidden />}
        {value}
      </p>
      {note && <p className="mt-0.5 text-xs text-muted-foreground">{note}</p>}
    </Card>
  );
  return to ? (
    <Link to={to} className="rounded-lg">
      {card}
    </Link>
  ) : (
    card
  );
}

export function DashboardPage() {
  const overview = useOverview();
  const jobs = useJobs();
  const { canOperate, canAdmin } = useAccess();
  const { run } = useJobActions();
  const o = overview.data;

  return (
    <>
      <PageHeader title="Dashboard" description="Health of every sync job at a glance." />
      <ErrorAlert error={overview.error ?? jobs.error} />
      {o && (
        <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-6">
          <Tile label="Active jobs" value={formatNumber(o.active)} note={`${o.jobs} total, ${o.paused} paused`} to="/jobs?status=ACTIVE" />
          <Tile label="Running now" value={formatNumber(o.running)} />
          <Tile
            label="Needs review"
            value={formatNumber(o.needsReview)}
            tone={o.needsReview > 0 ? "warning" : undefined}
            to={o.needsReview > 0 ? "/jobs?status=NEEDS_REVIEW" : undefined}
          />
          <Tile label="Failing" value={formatNumber(o.failing)} note="Last run failed" tone={o.failing > 0 ? "critical" : undefined} />
          <Tile label="Runs (24 h)" value={formatNumber(o.runs24h)} note={`${o.failedRuns24h} failed`} />
          <Tile label="Rows changed (24 h)" value={formatNumber(o.rowsChanged24h)} note="Upserts and deletes" />
        </div>
      )}
      <Card>
        <CardHeader title="Jobs" />
        {jobs.isPending ? (
          <Spinner />
        ) : !jobs.data?.length ? (
          <Empty title="No jobs yet">
            {canAdmin ? (
              <>
                Add a <Link className="text-primary hover:underline" to="/connections">connection</Link>, then{" "}
                <Link className="text-primary hover:underline" to="/jobs/new">create a job</Link>.
              </>
            ) : (
              "An administrator has not set up any jobs."
            )}
          </Empty>
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Job</Th>
                <Th>Status</Th>
                <Th>Last run</Th>
                <Th>Duration</Th>
                <Th className="text-right">Changes</Th>
                {canOperate && <Th className="w-0" />}
              </tr>
            </thead>
            <tbody>
              {jobs.data.map((job) => (
                <tr key={job.id} className="hover:bg-muted/50">
                  <Td>
                    <Link to={`/jobs/${job.id}`} className="font-medium hover:underline">
                      {job.name}
                    </Link>
                  </Td>
                  <Td>
                    <JobStatusBadge status={job.status} running={job.running} />
                  </Td>
                  <Td>
                    <div className="flex flex-wrap items-center gap-x-2">
                      <RunStateBadge run={job.lastRun} />
                      {job.lastRun && <span className="text-xs text-muted-foreground">{formatRelative(job.lastRun.startedAt)}</span>}
                    </div>
                  </Td>
                  <Td className="tabular-nums">{job.lastRun ? formatDuration(job.lastRun.startedAt, job.lastRun.finishedAt) : "—"}</Td>
                  <Td className="text-right tabular-nums">
                    {job.lastRun ? formatNumber(job.lastRun.upserts + job.lastRun.deletes) : "—"}
                  </Td>
                  {canOperate && (
                    <Td>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={job.running}
                        loading={run.isPending && run.variables?.job.id === job.id}
                        onClick={() => run.mutate({ job })}
                      >
                        <Play aria-hidden /> Run now
                      </Button>
                    </Td>
                  )}
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </>
  );
}
