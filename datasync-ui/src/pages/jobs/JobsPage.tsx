import { Pause, Play, Plus } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import type { JobView, Schemas } from "@/api/client";
import { useAccess, useConnections, useJobs } from "@/api/queries";
import { ErrorAlert } from "@/components/ErrorAlert";
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
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<Schemas["Status"] | "">("");
  const [connection, setConnection] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());

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
  const bulk = (paused: boolean) => {
    chosen.forEach((job) => setPaused.mutate({ job, paused }));
    setSelected(new Set());
  };

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
        <Input aria-label="Search jobs" placeholder="Search jobs…" className="w-56" value={search} onChange={(e) => setSearch(e.target.value)} />
        <Select aria-label="Status" className="w-44" value={status} onChange={(e) => setStatus(e.target.value as typeof status)}>
          <option value="">All statuses</option>
          <option value="ACTIVE">Active</option>
          <option value="PAUSED">Paused</option>
          <option value="NEEDS_REVIEW">Needs review</option>
        </Select>
        <Select aria-label="Connection" className="w-52" value={connection} onChange={(e) => setConnection(e.target.value)}>
          <option value="">All connections</option>
          {connections.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </Select>
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
          <Empty title={jobs.data?.length ? "No jobs match these filters" : "No jobs yet"} />
        ) : (
          <Table>
            <thead>
              <tr>
                {canOperate && (
                  <Th className="w-0">
                    <Checkbox
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
