import { Pause, Play, Plus, Search, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import type { JobView, Schemas } from "@/api/client";
import { useAccess, useConnections, useJobs } from "@/api/queries";
import { ErrorAlert } from "@/components/ErrorAlert";
import { toast } from "@/components/Toaster";
import { JobStatusBadge, RunStateBadge } from "@/components/StatusBadge";
import { Button } from "@/components/ui/button";
import { Checkbox, Input, Select } from "@/components/ui/form";
import { Card, Empty, PageHeader, Spinner, Table, Td, Th } from "@/components/ui/layout";
import { formatRelative } from "@/lib/format";
import { useConnectionNames, useJobActions } from "./actions";

const sinkConnections = (job: JobView) => job.config.sinks.map((s) => String(s.connection));

export function JobsPage() {
  const jobs = useJobs();
  const connections = useConnections().data ?? [];
  const connectionName = useConnectionNames();
  const { canOperate, canAdmin } = useAccess();
  const { run, setPaused } = useJobActions();
  const navigate = useNavigate();
  // filters live in the URL so they survive opening a job and coming back, and can be linked to
  const [params, setParams] = useSearchParams();
  const search = params.get("q") ?? "";
  const status = (params.get("status") ?? "") as Schemas["Status"] | "";
  const connection = params.get("connection") ?? "";
  const setFilter = (name: string, value: string) =>
    setParams(
      (p) => {
        if (value) p.set(name, value);
        else p.delete(name);
        return p;
      },
      { replace: true },
    );
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const selectAll = useRef<HTMLInputElement>(null);

  const visible = (jobs.data ?? []).filter(
    (j) =>
      j.name.toLowerCase().includes(search.toLowerCase()) &&
      (!status || j.status === status) &&
      (!connection || j.config.source.connection === connection || sinkConnections(j).includes(connection)),
  );
  const chosen = visible.filter((j) => selected.has(j.id));
  const toggle = (id: string) =>
    setSelected((s) => {
      const next = new Set(s);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  const bulk = async (paused: boolean) => {
    // only jobs whose state actually changes; failures already show their own toast
    const targets = chosen.filter((j) => (j.status === "PAUSED") !== paused);
    setSelected(new Set());
    const results = await Promise.allSettled(targets.map((job) => setPaused.mutateAsync({ job, paused })));
    const done = results.filter((r) => r.status === "fulfilled").length;
    if (done > 0) toast.success(`${paused ? "Paused" : "Resumed"} ${done} ${done === 1 ? "job" : "jobs"}`);
    else if (targets.length === 0) toast.success(`Selected jobs are already ${paused ? "paused" : "active"}`);
  };
  const filtered = Boolean(search || status || connection);
  const someChosen = chosen.length > 0 && chosen.length < visible.length;
  useEffect(() => {
    if (selectAll.current) selectAll.current.indeterminate = someChosen;
  }, [someChosen]);

  return (
    <>
      <PageHeader
        title="Jobs"
        description="Each job syncs one source table or view to its sinks."
        actions={
          canAdmin && (
            <Button onClick={() => navigate("/jobs/new")}>
              <Plus aria-hidden /> New job
            </Button>
          )
        }
      />
      <ErrorAlert error={jobs.error} />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <div className="relative w-full sm:w-56">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-subtle-foreground" aria-hidden />
          <Input
            type="search"
            aria-label="Search jobs"
            placeholder="Search jobs…"
            className="pl-8"
            value={search}
            onChange={(e) => setFilter("q", e.target.value)}
          />
        </div>
        <Select aria-label="Status" className="w-44" value={status} onChange={(e) => setFilter("status", e.target.value)}>
          <option value="">All statuses</option>
          <option value="ACTIVE">Active</option>
          <option value="PAUSED">Paused</option>
          <option value="NEEDS_REVIEW">Needs review</option>
        </Select>
        <Select aria-label="Connection" className="w-52" value={connection} onChange={(e) => setFilter("connection", e.target.value)}>
          <option value="">All connections</option>
          {connections.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </Select>
        {filtered && (
          <Button size="sm" variant="ghost" onClick={() => setParams({}, { replace: true })}>
            <X aria-hidden /> Clear filters
          </Button>
        )}
        {canOperate && chosen.length > 0 && (
          <div className="ml-auto flex items-center gap-2">
            <span className="text-sm text-muted-foreground">{chosen.length} selected</span>
            <Button size="sm" variant="outline" onClick={() => bulk(true)}>
              <Pause aria-hidden /> Pause
            </Button>
            <Button size="sm" variant="outline" onClick={() => bulk(false)}>
              <Play aria-hidden /> Resume
            </Button>
          </div>
        )}
      </div>
      <Card>
        {jobs.isPending ? (
          <Spinner />
        ) : visible.length === 0 ? (
          <Empty title={jobs.data?.length ? "No jobs match these filters" : "No jobs yet"}>
            {jobs.data?.length ? (
              <button className="text-primary hover:underline" onClick={() => setParams({}, { replace: true })}>
                Clear filters
              </button>
            ) : (
              canAdmin && (
                <Link className="text-primary hover:underline" to="/jobs/new">
                  Create the first job
                </Link>
              )
            )}
          </Empty>
        ) : (
          <Table>
            <thead>
              <tr>
                {canOperate && (
                  <Th className="w-0">
                    <Checkbox
                      ref={selectAll}
                      aria-label="Select all"
                      checked={chosen.length === visible.length}
                      onChange={(e) => setSelected(e.target.checked ? new Set(visible.map((j) => j.id)) : new Set())}
                    />
                  </Th>
                )}
                <Th>Job</Th>
                <Th>Status</Th>
                <Th>Source</Th>
                <Th>Sinks</Th>
                <Th>Last run</Th>
                {canOperate && <Th className="w-0" />}
              </tr>
            </thead>
            <tbody>
              {visible.map((job) => (
                <tr key={job.id} className="hover:bg-muted/50">
                  {canOperate && (
                    <Td>
                      <Checkbox aria-label={`Select ${job.name}`} checked={selected.has(job.id)} onChange={() => toggle(job.id)} />
                    </Td>
                  )}
                  <Td>
                    <Link to={`/jobs/${job.id}`} className="font-medium hover:underline">
                      {job.name}
                    </Link>
                  </Td>
                  <Td>
                    <JobStatusBadge status={job.status} running={job.running} />
                  </Td>
                  <Td>
                    <p>{connectionName(job.config.source.connection)}</p>
                    <p className="font-mono text-xs text-muted-foreground">{job.config.source.object}</p>
                  </Td>
                  <Td>
                    {job.config.sinks.map((s, i) => (
                      <div key={i}>
                        <p>{connectionName(s.connection)}</p>
                        <p className="font-mono text-xs text-muted-foreground">{String(s.target)}</p>
                      </div>
                    ))}
                  </Td>
                  <Td>
                    <RunStateBadge run={job.lastRun} />
                    {job.lastRun && <p className="text-xs text-muted-foreground">{formatRelative(job.lastRun.startedAt)}</p>}
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
