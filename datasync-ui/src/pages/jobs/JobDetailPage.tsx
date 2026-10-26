import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Pause, Pencil, Play, RefreshCw, Trash2 } from "lucide-react";
import { lazy, Suspense, useState, type ReactNode } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, call, type JobView, type RunView } from "@/api/client";
import { keys, useAccess, useJob, useRuns } from "@/api/queries";
import { AuditTable } from "@/components/AuditTable";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { ErrorAlert } from "@/components/ErrorAlert";
import { JobStatusBadge, RunStateBadge } from "@/components/StatusBadge";
import { toast } from "@/components/Toaster";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, Empty, PageHeader, Spinner, Table, Td, Th } from "@/components/ui/layout";
import { Tabs } from "@/components/ui/tabs";
import { formatDateTime, formatDuration, formatNumber, formatRelative } from "@/lib/format";
import { toYaml } from "@/lib/yaml";
import { nextRunLabel, useConnectionNames, useJobActions } from "./actions";
import { describeMode, describeSchedule } from "./jobForm";

// Recharts is large; load it only when a job page shows the chart
const RunsChart = lazy(() => import("./RunsChart"));

export function JobDetailPage() {
  const { id = "" } = useParams();
  const job = useJob(id);
  const runs = useRuns(id);
  const { canOperate, canAdmin } = useAccess();
  const { run, setPaused } = useJobActions();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [confirm, setConfirm] = useState<"deletes" | "delete" | "reconcile" | null>(null);
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/jobs/{id}", { params: { path: { id } } })),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.jobs });
      toast.success("Job deleted");
      navigate("/jobs");
    },
    onError: toast.error,
  });

  if (job.isPending) return <Spinner />;
  if (job.error) return <ErrorAlert error={job.error} />;
  const j = job.data;
  const review = j.status === "NEEDS_REVIEW";
  // in watermark modes only a reconcile reads the whole source, so only it can find and apply deletes
  const incremental = j.config.mode !== "SNAPSHOT_DIFF";

  return (
    <>
      <PageHeader
        title={j.name}
        description={<JobStatusBadge status={j.status} running={j.running} />}
        actions={
          <>
            {canOperate && (
              <>
                <Button disabled={j.running} loading={run.isPending} onClick={() => run.mutate({ job: j })}>
                  <Play aria-hidden /> Run now
                </Button>
                {incremental && (
                  <Button variant="outline" disabled={j.running} onClick={() => setConfirm("reconcile")}>
                    <RefreshCw aria-hidden /> Reconcile
                  </Button>
                )}
                <Button variant="outline" loading={setPaused.isPending} onClick={() => setPaused.mutate({ job: j, paused: j.status !== "PAUSED" })}>
                  {j.status === "PAUSED" ? <Play aria-hidden /> : <Pause aria-hidden />}
                  {j.status === "PAUSED" ? "Resume" : "Pause"}
                </Button>
              </>
            )}
            {canAdmin && (
              <>
                <Button variant="outline" onClick={() => navigate(`/jobs/${j.id}/edit`)}>
                  <Pencil aria-hidden /> Edit
                </Button>
                <Button variant="destructive-outline" disabled={j.running} onClick={() => setConfirm("delete")}>
                  <Trash2 aria-hidden /> Delete
                </Button>
              </>
            )}
          </>
        }
      />
      {review && (
        <div className="mb-6 flex flex-wrap items-start justify-between gap-4 rounded-lg border border-warning/50 bg-warning/10 px-4 py-3 text-sm">
          <div className="flex gap-2">
            <AlertTriangle className="mt-0.5 size-4 shrink-0 text-warning-text" aria-hidden />
            <div>
              <p className="font-medium">The last run stopped before deleting rows</p>
              <p className="mt-0.5 text-muted-foreground">
                {j.lastRun?.error ?? "More rows would be deleted than the delete guard allows"}. Check the source. If the deletes are real,
                apply them.
              </p>
            </div>
          </div>
          {canOperate && (
            <Button variant="destructive-outline" disabled={j.running} onClick={() => setConfirm("deletes")}>
              Apply deletes
            </Button>
          )}
        </div>
      )}
      <Tabs
        tabs={[
          { value: "overview", label: "Overview", content: <Overview job={j} runs={runs.data ?? []} /> },
          { value: "runs", label: "Runs", content: <RunsTable runs={runs.data} error={runs.error} /> },
          {
            value: "config",
            label: "Config",
            content: (
              <Card className="p-5">
                <ConfigYaml job={j} />
              </Card>
            ),
          },
          {
            value: "audit",
            label: "Audit",
            content: (
              <Card>
                <AuditTable entity={`job:${j.id}`} />
              </Card>
            ),
          },
        ]}
      />
      <ConfirmDialog
        open={confirm === "deletes"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title="Apply deletes?"
        effect={`This ${incremental ? "reconciles" : "runs"} ${j.name} with the delete guard turned off. Every key missing from the source is deleted in every sink (soft or hard, as each sink is configured).`}
        confirmLabel="Run and apply deletes"
        destructive
        loading={run.isPending}
        onConfirm={() => run.mutate({ job: j, allowDeletes: true, reconcile: incremental }, { onSettled: () => setConfirm(null) })}
      />
      <ConfirmDialog
        open={confirm === "reconcile"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title="Reconcile now?"
        effect={`Reads the whole of ${j.config.source.object} and compares it with every sink: it fixes changes the watermark missed and applies rows deleted at the source. On a large table this takes as long as a full load. The delete guard still applies.`}
        confirmLabel="Reconcile"
        loading={run.isPending}
        onConfirm={() => run.mutate({ job: j, reconcile: true }, { onSettled: () => setConfirm(null) })}
      />
      <ConfirmDialog
        open={confirm === "delete"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title={`Delete ${j.name}?`}
        effect="The job, its run history and its change-detection index are removed. Target tables and their data are left untouched."
        confirmLabel="Delete job"
        destructive
        loading={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </>
  );
}

function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 text-sm tabular-nums">{children}</dd>
    </div>
  );
}

function Overview({ job, runs }: { job: JobView; runs: RunView[] }) {
  const connectionName = useConnectionNames();
  const last = job.lastRun;
  const c = job.config;
  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <Card className="lg:col-span-2">
        <CardHeader title="Rows changed per run" description="Last 30 finished runs" />
        <div className="p-4">
          <Suspense fallback={<Spinner />}>
            <RunsChart runs={runs} />
          </Suspense>
        </div>
      </Card>
      <Card>
        <CardHeader title="Last run" />
        <dl className="grid grid-cols-2 gap-4 p-5">
          <Stat label={last?.kind === "RECONCILE" ? "Result (reconcile)" : "Result"}>
            <RunStateBadge run={last} />
          </Stat>
          <Stat label="Started">{last ? formatRelative(last.startedAt) : "—"}</Stat>
          <Stat label="Duration">{last ? formatDuration(last.startedAt, last.finishedAt) : "—"}</Stat>
          <Stat label="Rows read">{last ? formatNumber(last.rowsRead) : "—"}</Stat>
          <Stat label="Upserts">{last ? formatNumber(last.upserts) : "—"}</Stat>
          <Stat label="Deletes">{last ? formatNumber(last.deletes) : "—"}</Stat>
          <Stat label="Next run">{nextRunLabel(job)}</Stat>
          {c.mode === "HYBRID" && <Stat label="Next reconcile">{job.nextReconcileAt ? formatRelative(job.nextReconcileAt) : "—"}</Stat>}
        </dl>
        {last?.error && last.state === "FAILED" && (
          <div className="px-5 pb-5">
            <ErrorAlert error={last.error} />
          </div>
        )}
      </Card>
      <Card className="lg:col-span-3">
        <CardHeader title="Configuration" />
        <dl className="grid gap-4 p-5 sm:grid-cols-2 lg:grid-cols-4">
          <Stat label="Source">
            {connectionName(c.source.connection)} · <span className="font-mono text-xs">{c.source.object}</span>
          </Stat>
          <Stat label="Sinks">
            {c.sinks.map((s, i) => (
              <p key={i}>
                {connectionName(s.connection)} · <span className="font-mono text-xs">{String(s.target)}</span> ·{" "}
                {String(s.deleteMode ?? "SOFT").toLowerCase()} deletes
              </p>
            ))}
          </Stat>
          <Stat label="Schedule">{describeSchedule(c)}</Stat>
          <Stat label="Change detection">
            {describeMode(c)}; batches of {formatNumber(c.batchSize)}
          </Stat>
          {c.mode !== "SNAPSHOT_DIFF" && (
            <Stat label="Watermark reached">
              {job.watermarkValue ? <span className="font-mono text-xs">{job.watermarkValue}</span> : "None yet: the next run reads everything"}
            </Stat>
          )}
          <Stat label="Delete guard">Stop above {c.maxDeletePercent}% deleted</Stat>
        </dl>
      </Card>
    </div>
  );
}

function RunsTable({ runs, error }: { runs?: RunView[]; error: unknown }) {
  if (error) return <ErrorAlert error={error} />;
  if (!runs) return <Spinner />;
  return (
    <Card>
      {runs.length === 0 ? (
        <Empty title="No runs yet" />
      ) : (
        <Table>
          <thead>
            <tr>
              <Th>Started</Th>
              <Th>Result</Th>
              <Th className="text-right">Duration</Th>
              <Th className="text-right">Read</Th>
              <Th className="text-right">Upserts</Th>
              <Th className="text-right">Deletes</Th>
              <Th className="text-right">Unchanged</Th>
            </tr>
          </thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id} className="align-top">
                <Td className="whitespace-nowrap">{formatDateTime(r.startedAt)}</Td>
                <Td>
                  <RunStateBadge run={r} />
                  {r.kind === "RECONCILE" && <p className="text-xs text-muted-foreground">Reconcile</p>}
                  {r.error && (
                    <details className="mt-1 max-w-md text-xs">
                      <summary className="cursor-pointer text-muted-foreground">Details</summary>
                      <p className="mt-1 font-mono break-words">{r.error}</p>
                    </details>
                  )}
                </Td>
                <Td className="text-right tabular-nums">{formatDuration(r.startedAt, r.finishedAt)}</Td>
                <Td className="text-right tabular-nums">{formatNumber(r.rowsRead)}</Td>
                <Td className="text-right tabular-nums">{formatNumber(r.upserts)}</Td>
                <Td className="text-right tabular-nums">{formatNumber(r.deletes)}</Td>
                <Td className="text-right tabular-nums">{formatNumber(r.unchanged)}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  );
}

/** The stored config as YAML, with connection names in place of ids for readability. */
function ConfigYaml({ job }: { job: JobView }) {
  const connectionName = useConnectionNames();
  const c = job.config;
  const readable = {
    ...c,
    source: { ...c.source, connection: connectionName(c.source.connection) },
    sinks: c.sinks.map((s) => ({ ...s, connection: connectionName(s.connection) })),
  };
  return <pre className="overflow-x-auto font-mono text-xs leading-relaxed">{toYaml(readable)}</pre>;
}
