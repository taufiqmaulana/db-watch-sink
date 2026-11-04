import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Send, Upload } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { api, call, type Schemas } from "@/api/client";
import { keys } from "@/api/queries";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { ErrorAlert } from "@/components/ErrorAlert";
import { toast } from "@/components/Toaster";
import { Button } from "@/components/ui/button";
import { Checkbox, Field, Input } from "@/components/ui/form";
import { Card, CardHeader, Spinner, Table, Td, Th } from "@/components/ui/layout";

const count = (min: number, max: number) =>
  z.number({ error: "Enter a number" }).int("Enter a whole number").min(min, `At least ${min}`).max(max, `At most ${max}`);

const alertSchema = z.object({
  emails: z.string(),
  webhookUrl: z.string().trim().refine((s) => !s || /^https?:\/\/.+/i.test(s), "Must start with http:// or https://"),
  failedRuns: count(0, 100),
  lagMinutes: count(0, 10_080),
  deadLetters: z.boolean(),
  deleteGuard: z.boolean(),
  retentionDays: count(1, 3650),
});
type AlertValues = z.infer<typeof alertSchema>;

function toSettings(v: AlertValues): Schemas["AppSettings"] {
  return {
    alerts: {
      emails: v.emails.split(/[\s,;]+/).filter(Boolean),
      webhookUrl: v.webhookUrl || null,
      failedRuns: v.failedRuns,
      lagMinutes: v.lagMinutes,
      deadLetters: v.deadLetters,
      deleteGuard: v.deleteGuard,
    },
    retentionDays: v.retentionDays,
  };
}

/** Alert channels and rules (design 12), and how long run history is kept. */
export function AlertsPanel() {
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: keys.settings, queryFn: () => call(api.GET("/api/v1/settings")) });
  const s = settings.data;
  const form = useForm<AlertValues>({
    resolver: zodResolver(alertSchema),
    values: s && {
      emails: s.alerts.emails.join(", "),
      webhookUrl: s.alerts.webhookUrl ?? "",
      failedRuns: s.alerts.failedRuns,
      lagMinutes: s.alerts.lagMinutes,
      deadLetters: s.alerts.deadLetters,
      deleteGuard: s.alerts.deleteGuard,
      retentionDays: s.retentionDays,
    },
  });
  const save = useMutation({
    mutationFn: (v: AlertValues) => call(api.PUT("/api/v1/settings", { body: toSettings(v) })),
    onSuccess: (saved) => {
      queryClient.setQueryData(keys.settings, saved);
      toast.success("Saved the alert and retention settings.");
    },
  });
  const test = useMutation({
    mutationFn: (v: AlertValues) => call(api.POST("/api/v1/settings/alerts/test", { body: toSettings(v).alerts })),
    onSuccess: (result) => {
      const failed = Object.entries(result.failures);
      if (result.sent.length) toast.success(`Sent a test alert by ${result.sent.join(" and ")}.`);
      if (failed.length) toast.error(failed.map(([channel, error]) => `${channel}: ${error}`).join("; "));
    },
    onError: (e) => toast.error(e),
  });
  const { errors } = form.formState;

  if (settings.isPending) return <Spinner />;
  return (
    <Card>
      <CardHeader title="Alerts and retention" description="Who hears about problems, and how long run history is kept." />
      <form className="grid max-w-2xl gap-5 p-5" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
        <ErrorAlert error={settings.error ?? save.error} />
        <Field label="Email recipients" hint="Separate addresses with commas. The server's SMTP settings (QUARKUS_MAILER_*) send them.">
          {(id) => <Input id={id} placeholder="ops@example.com, dba@example.com" {...form.register("emails")} />}
        </Field>
        <Field
          label="Webhook URL"
          hint='Receives {"text": "...", "event", "jobId", "at"} as JSON, which Slack and Teams incoming webhooks accept.'
          error={errors.webhookUrl?.message}
        >
          {(id) => <Input id={id} className="font-mono" placeholder="https://hooks.slack.com/services/..." {...form.register("webhookUrl")} />}
        </Field>
        <fieldset className="grid gap-3">
          <legend className="mb-2 text-sm font-medium">Alert when</legend>
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span>a job fails</span>
            <Input type="number" min={0} max={100} aria-label="Failed runs in a row" className="w-20" {...form.register("failedRuns", { valueAsNumber: true })} />
            <span>runs in a row (0 = never)</span>
            {errors.failedRuns && <span className="text-xs text-critical-text">{errors.failedRuns.message}</span>}
          </div>
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span>an active job has no successful run for</span>
            <Input type="number" min={0} max={10_080} aria-label="Lag minutes" className="w-24" {...form.register("lagMinutes", { valueAsNumber: true })} />
            <span>minutes (0 = never)</span>
            {errors.lagMinutes && <span className="text-xs text-critical-text">{errors.lagMinutes.message}</span>}
          </div>
          <label className="flex items-center gap-2 text-sm">
            <Checkbox {...form.register("deadLetters")} />a sink moves a batch to the dead letters or falls too far behind
          </label>
          <label className="flex items-center gap-2 text-sm">
            <Checkbox {...form.register("deleteGuard")} />the delete guard stops a run
          </label>
        </fieldset>
        <Field
          label="Keep run history for (days)"
          hint="Older finished runs and resolved dead letters are removed hourly. Each job keeps its latest run."
          error={errors.retentionDays?.message}
        >
          {(id) => <Input id={id} type="number" min={1} max={3650} className="w-28" {...form.register("retentionDays", { valueAsNumber: true })} />}
        </Field>
        <div className="flex gap-2">
          <Button type="submit" loading={save.isPending}>
            Save
          </Button>
          <Button type="button" variant="outline" loading={test.isPending} onClick={form.handleSubmit((v) => test.mutate(v))}>
            <Send aria-hidden /> Send test alert
          </Button>
        </div>
      </form>
    </Card>
  );
}

const ACTIONS: Record<Schemas["Action"], string> = { CREATE: "Create", UPDATE: "Update", UNCHANGED: "No change" };

const importYaml = (yaml: string, dryRun: boolean) =>
  call(
    api.POST("/api/v1/import", {
      params: { query: { dryRun } },
      body: yaml,
      bodySerializer: (b) => b,
      headers: { "Content-Type": "application/yaml" },
    }),
  );

/** Configuration as code (design 13): download every connection and job as YAML, or import a file with a preview first. */
export function ConfigPanel({ canImport }: { canImport: boolean }) {
  const queryClient = useQueryClient();
  const [yaml, setYaml] = useState<string | null>(null);
  const [fileName, setFileName] = useState("");
  const [confirming, setConfirming] = useState(false);
  const download = useMutation({
    mutationFn: () => call(api.GET("/api/v1/export", { parseAs: "text" })),
    onSuccess: (text) => {
      const link = document.createElement("a");
      link.href = URL.createObjectURL(new Blob([text], { type: "application/yaml" }));
      link.download = `datasync-${new Date().toISOString().slice(0, 10)}.yaml`;
      link.click();
      URL.revokeObjectURL(link.href);
    },
    onError: (e) => toast.error(e),
  });
  const preview = useMutation({ mutationFn: (text: string) => importYaml(text, true) });
  const apply = useMutation({
    mutationFn: (text: string) => importYaml(text, false),
    onSuccess: (result) => {
      setConfirming(false);
      setYaml(null);
      preview.reset();
      queryClient.invalidateQueries();
      const changed = result.items.filter((i) => i.action !== "UNCHANGED").length;
      toast.success(changed ? `Imported ${changed} change${changed === 1 ? "" : "s"}.` : "Nothing to change.");
    },
    onError: (e) => {
      setConfirming(false);
      toast.error(e);
    },
  });
  const items = preview.data?.items ?? [];
  const changes = items.filter((i) => i.action !== "UNCHANGED").length;

  return (
    <Card>
      <CardHeader
        title="Configuration as YAML"
        description="Keep connections and jobs in Git, or move them between servers. Passwords are exported as ${env:...} references; on import, an unset one keeps the stored password."
      />
      <div className="grid gap-5 p-5">
        <div>
          <Button variant="outline" loading={download.isPending} onClick={() => download.mutate()}>
            <Download aria-hidden /> Download YAML
          </Button>
        </div>
        {canImport && (
          <div className="grid max-w-3xl gap-3">
            <Field label="Import a file" hint="Connections and jobs are matched by name: new ones are created, changed ones updated, others left alone.">
              {(id) => (
                <Input
                  id={id}
                  type="file"
                  accept=".yaml,.yml,application/yaml,text/yaml"
                  onChange={async (e) => {
                    const file = e.target.files?.[0];
                    if (!file) return;
                    const text = await file.text();
                    setFileName(file.name);
                    setYaml(text);
                    preview.mutate(text);
                  }}
                />
              )}
            </Field>
            <ErrorAlert error={preview.error} title={`${fileName} cannot be imported`} />
            {preview.isPending && <Spinner label="Checking the file…" />}
            {preview.data && (
              <>
                <Table>
                  <thead>
                    <tr>
                      <Th>Kind</Th>
                      <Th>Name</Th>
                      <Th>Import will</Th>
                    </tr>
                  </thead>
                  <tbody>
                    {items.map((i) => (
                      <tr key={`${i.kind}:${i.name}`}>
                        <Td>{i.kind === "JOB" ? "Job" : "Connection"}</Td>
                        <Td className="font-medium">{i.name}</Td>
                        <Td className={i.action === "UNCHANGED" ? "text-muted-foreground" : "font-medium"}>{ACTIONS[i.action]}</Td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
                <div className="flex gap-2">
                  <Button disabled={changes === 0} onClick={() => setConfirming(true)}>
                    <Upload aria-hidden /> {changes ? `Apply ${changes} change${changes === 1 ? "" : "s"}` : "Nothing to change"}
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => {
                      setYaml(null);
                      preview.reset();
                    }}
                  >
                    Cancel
                  </Button>
                </div>
              </>
            )}
          </div>
        )}
      </div>
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={`Import ${fileName}?`}
        effect={`${changes} connection${changes === 1 ? "" : "s"} or job${changes === 1 ? "" : "s"} will be created or updated as listed. New jobs start active, on their schedule.`}
        confirmLabel="Import"
        loading={apply.isPending}
        onConfirm={() => yaml && apply.mutate(yaml)}
      />
    </Card>
  );
}
