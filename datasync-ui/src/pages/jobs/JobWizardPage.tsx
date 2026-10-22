import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { Check, Plus, Search, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useFieldArray, useForm, type UseFormReturn } from "react-hook-form";
import { useNavigate, useParams } from "react-router-dom";
import { api, call, type JobDef, type Preview } from "@/api/client";
import { keys, useAccess, useConnections, useJob, useObjects, useObjectSchema } from "@/api/queries";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { ErrorAlert } from "@/components/ErrorAlert";
import { toast } from "@/components/Toaster";
import { Button } from "@/components/ui/button";
import { Checkbox, Field, Input, Select } from "@/components/ui/form";
import { Card, PageHeader, Spinner, Table, Td, Th } from "@/components/ui/layout";
import { cn } from "@/lib/format";
import { columnRows, emptyJob, fromJobDef, jobSchema, newSink, toJobDef, typeLabel, type JobFormValues } from "./jobForm";

type Form = UseFormReturn<JobFormValues>;

const STEPS: { title: string; fields: (keyof JobFormValues)[] }[] = [
  { title: "Source", fields: ["name", "sourceConnection", "sourceObject"] },
  { title: "Columns", fields: ["columns"] },
  { title: "Change detection", fields: ["batchSize", "fetchSize", "queryTimeoutSeconds", "maxDeletePercent"] },
  { title: "Sinks", fields: ["sinks"] },
  { title: "Dry run", fields: [] },
];

export function JobWizardPage() {
  const { id } = useParams();
  const existing = useJob(id);
  const { canAdmin } = useAccess();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [step, setStep] = useState(0);
  const form = useForm<JobFormValues>({ resolver: zodResolver(jobSchema), defaultValues: emptyJob, mode: "onTouched" });

  // load a stored job once; later refetches (live updates) must not wipe edits in progress
  const loaded = useRef(false);
  useEffect(() => {
    if (existing.data && !loaded.current) {
      loaded.current = true;
      form.reset(fromJobDef(existing.data.config));
    }
  }, [existing.data, form]);

  const preview = useMutation({ mutationFn: (config: JobDef) => call(api.POST("/api/v1/jobs/validate", { body: config })) });
  const save = useMutation({
    mutationFn: (config: JobDef) =>
      existing.data
        ? call(api.PUT("/api/v1/jobs/{id}", { params: { path: { id: existing.data.id } }, body: { config, version: existing.data.version } }))
        : call(api.POST("/api/v1/jobs", { body: { config, version: 0 } })),
    onSuccess: (job) => {
      queryClient.invalidateQueries({ queryKey: keys.jobs });
      toast.success(`Saved ${job.name}`);
      navigate(`/jobs/${job.id}`);
    },
  });

  if (!canAdmin) return <p className="text-sm text-muted-foreground">Only admins can create or edit jobs.</p>;
  if (id && existing.isPending) return <Spinner />;
  if (id && existing.error) return <ErrorAlert error={existing.error} />;

  const next = async () => {
    if (await form.trigger(STEPS[step].fields)) setStep(step + 1);
  };

  return (
    <>
      <PageHeader
        title={existing.data ? `Edit ${existing.data.name}` : "New job"}
        description="Sync one table or view to one or more targets."
        actions={
          <Button variant="outline" onClick={() => navigate(existing.data ? `/jobs/${existing.data.id}` : "/jobs")}>
            Cancel
          </Button>
        }
      />
      <ol className="mb-6 flex flex-wrap gap-x-6 gap-y-2 text-sm">
        {STEPS.map((s, i) => (
          <li key={s.title}>
            <button
              className={cn("flex items-center gap-2", i === step ? "font-medium" : "text-muted-foreground", i > step && "cursor-default")}
              onClick={() => i < step && setStep(i)}
              aria-current={i === step ? "step" : undefined}
            >
              <span
                className={cn(
                  "flex size-6 items-center justify-center rounded-full border text-xs",
                  i < step && "border-primary bg-primary text-primary-foreground",
                  i === step && "border-primary text-primary",
                )}
              >
                {i < step ? <Check className="size-3.5" aria-hidden /> : i + 1}
              </span>
              {s.title}
            </button>
          </li>
        ))}
      </ol>
      <Card className="p-5">
        {step === 0 && <SourceStep form={form} />}
        {step === 1 && <ColumnsStep form={form} saved={existing.data?.config} />}
        {step === 2 && <DetectionStep form={form} />}
        {step === 3 && <SinksStep form={form} />}
        {step === 4 && <DryRunStep form={form} preview={preview} saveError={save.error} />}
        <div className="mt-6 flex justify-between gap-2 border-t border-border pt-5">
          <Button variant="outline" disabled={step === 0} onClick={() => setStep(step - 1)}>
            Back
          </Button>
          {step < STEPS.length - 1 ? (
            <Button onClick={next}>Next</Button>
          ) : (
            <Button
              disabled={!preview.isSuccess}
              title={preview.isSuccess ? undefined : "Save after a successful dry run"}
              loading={save.isPending}
              onClick={() => save.mutate(toJobDef(form.getValues()))}
            >
              Save job
            </Button>
          )}
        </div>
      </Card>
    </>
  );
}

function SourceStep({ form }: { form: Form }) {
  const connections = (useConnections().data ?? []).filter((c) => c.role !== "SINK");
  const { errors } = form.formState;
  const connectionId = form.watch("sourceConnection");
  const [schemaFilter, setSchemaFilter] = useState("");
  const [browsing, setBrowsing] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const objects = useObjects(connectionId, browsing ?? "", browsing !== null && Boolean(connectionId));

  const pick = (qualified: string, name: string) => {
    form.setValue("sourceObject", qualified, { shouldValidate: true });
    if (!form.getValues("name")) form.setValue("name", `${name.toLowerCase()}_sync`, { shouldValidate: true });
  };
  const matches = (objects.data ?? []).filter((o) => `${o.schema}.${o.name}`.toLowerCase().includes(search.toLowerCase()));

  return (
    <div className="grid max-w-2xl gap-5">
      <Field label="Source connection" error={errors.sourceConnection?.message}>
        {(id) => (
          <Select id={id} {...form.register("sourceConnection", { onChange: () => setBrowsing(null) })}>
            <option value="">Choose…</option>
            {connections.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      {connectionId && (
        <div className="grid gap-3 rounded-md border border-border p-4">
          <p className="text-sm font-medium">Browse tables and views</p>
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              setBrowsing(schemaFilter.trim());
            }}
          >
            <Input aria-label="Schema" placeholder="Schema, e.g. HR (recommended on Oracle)" value={schemaFilter} onChange={(e) => setSchemaFilter(e.target.value)} />
            <Button type="submit" variant="outline" loading={objects.isFetching}>
              <Search aria-hidden /> Browse
            </Button>
          </form>
          <ErrorAlert error={objects.error} />
          {objects.data && (
            <>
              <Input aria-label="Filter objects" placeholder={`Filter ${objects.data.length} objects…`} value={search} onChange={(e) => setSearch(e.target.value)} />
              <ul className="max-h-64 overflow-y-auto rounded-md border border-border text-sm">
                {matches.slice(0, 300).map((o) => {
                  const qualified = o.schema ? `${o.schema}.${o.name}` : o.name;
                  return (
                    <li key={qualified}>
                      <button
                        className={cn("w-full px-3 py-1.5 text-left font-mono text-xs hover:bg-muted", form.watch("sourceObject") === qualified && "bg-muted font-semibold")}
                        onClick={() => pick(qualified, o.name)}
                      >
                        {qualified}
                      </button>
                    </li>
                  );
                })}
                {matches.length === 0 && <li className="px-3 py-2 text-muted-foreground">No matching objects.</li>}
              </ul>
            </>
          )}
        </div>
      )}
      <Field label="Table or view" hint="Schema-qualified, exactly as the database names it, e.g. HR.CUSTOMERS_V." error={errors.sourceObject?.message}>
        {(id) => <Input id={id} className="font-mono" {...form.register("sourceObject")} />}
      </Field>
      <Field label="Job name" error={errors.name?.message}>
        {(id) => <Input id={id} placeholder="customers_sync" {...form.register("name")} />}
      </Field>
    </div>
  );
}

function ColumnsStep({ form, saved }: { form: Form; saved?: JobDef }) {
  const [connectionId, object, columnsFor] = form.watch(["sourceConnection", "sourceObject", "columnsFor"]);
  const objectName = object.trim();
  const loadedFor = `${connectionId}|${objectName}`;
  const schema = useObjectSchema(connectionId, objectName);
  const { fields, replace } = useFieldArray({ control: form.control, name: "columns" });
  const errors = form.formState.errors.columns;

  useEffect(() => {
    if (!schema.data || columnsFor === loadedFor) return;
    const isSaved = saved && saved.source.connection === connectionId && saved.source.object === objectName;
    replace(columnRows(schema.data, isSaved ? saved : undefined));
    form.setValue("columnsFor", loadedFor);
  }, [schema.data, columnsFor, loadedFor, saved, connectionId, objectName, replace, form]);

  if (schema.isPending) return <Spinner label="Reading the source columns…" />;
  if (schema.error) return <ErrorAlert error={schema.error} title="Cannot read the source columns" />;

  return (
    <div className="grid gap-4">
      <p className="text-sm text-muted-foreground">
        Choose the columns to sync and the key that identifies a row. Keys come from the primary key when there is one; views need them
        picked. New source columns are picked up automatically unless you leave them out here.
      </p>
      {(errors?.message || errors?.root?.message) && <ErrorAlert error={errors.message ?? errors.root?.message} />}
      <Table>
        <thead>
          <tr>
            <Th className="w-0">Sync</Th>
            <Th className="w-0">Key</Th>
            <Th>Column</Th>
            <Th>Type</Th>
            <Th>Target name</Th>
          </tr>
        </thead>
        <tbody>
          {fields.map((f, i) => {
            const key = form.watch(`columns.${i}.key`);
            return (
              <tr key={f.id}>
                <Td>
                  <Checkbox aria-label={`Sync ${f.name}`} disabled={key} {...form.register(`columns.${i}.include`)} />
                </Td>
                <Td>
                  <Checkbox
                    aria-label={`${f.name} is a key column`}
                    {...form.register(`columns.${i}.key`, { onChange: (e) => e.target.checked && form.setValue(`columns.${i}.include`, true) })}
                  />
                </Td>
                <Td className="font-mono text-xs">{f.name}</Td>
                <Td className="text-xs text-muted-foreground">{f.type}</Td>
                <Td>
                  <Input aria-label={`Target name for ${f.name}`} placeholder={f.name} className="h-8 font-mono text-xs" {...form.register(`columns.${i}.rename`)} />
                </Td>
              </tr>
            );
          })}
        </tbody>
      </Table>
    </div>
  );
}

function DetectionStep({ form }: { form: Form }) {
  const { errors } = form.formState;
  const number = (name: "batchSize" | "fetchSize" | "queryTimeoutSeconds" | "maxDeletePercent") =>
    form.register(name, { valueAsNumber: true });
  return (
    <div className="grid max-w-2xl gap-5">
      <div className="rounded-md bg-muted px-4 py-3 text-sm">
        <p className="font-medium">Snapshot diff</p>
        <p className="mt-0.5 text-muted-foreground">
          Each run reads the whole source and compares it with the last run, so it needs no update timestamp and detects deletes. Runs are
          started with Run now; scheduling is not available yet.
        </p>
      </div>
      <Field
        label="Delete guard (%)"
        hint="Stop and ask for review when a run would delete more than this share of rows. Protects against a failed or partial source read."
        error={errors.maxDeletePercent?.message}
      >
        {(id) => <Input id={id} type="number" min={0} max={100} className="w-28" {...number("maxDeletePercent")} />}
      </Field>
      <div className="grid gap-5 sm:grid-cols-3">
        <Field label="Batch size" hint="Rows per query and per delivery." error={errors.batchSize?.message}>
          {(id) => <Input id={id} type="number" {...number("batchSize")} />}
        </Field>
        <Field label="Fetch size" hint="Rows per network round trip." error={errors.fetchSize?.message}>
          {(id) => <Input id={id} type="number" {...number("fetchSize")} />}
        </Field>
        <Field label="Query timeout (s)" error={errors.queryTimeoutSeconds?.message}>
          {(id) => <Input id={id} type="number" {...number("queryTimeoutSeconds")} />}
        </Field>
      </div>
    </div>
  );
}

function SinksStep({ form }: { form: Form }) {
  const connections = (useConnections().data ?? []).filter((c) => c.role !== "SOURCE");
  const { fields, append, remove } = useFieldArray({ control: form.control, name: "sinks" });
  const [hardDeleteFor, setHardDeleteFor] = useState<number | null>(null);
  const errors = form.formState.errors.sinks;
  const object = form.watch("sourceObject");
  const suggestedTarget = `public.${(object.split(".").pop() ?? "").toLowerCase()}`;

  useEffect(() => {
    form.getValues("sinks").forEach((s, i) => !s.target && form.setValue(`sinks.${i}.target`, suggestedTarget));
  }, [form, suggestedTarget]);

  return (
    <div className="grid gap-4">
      {errors?.message && <ErrorAlert error={errors.message} />}
      {fields.map((f, i) => {
        const e = errors?.[i];
        const deleteMode = form.watch(`sinks.${i}.deleteMode`);
        return (
          <div key={f.id} className="grid gap-4 rounded-md border border-border p-4">
            <div className="flex items-center justify-between">
              <p className="text-sm font-medium">Database sink {i + 1}</p>
              {fields.length > 1 && (
                <Button size="icon" variant="ghost" aria-label={`Remove sink ${i + 1}`} onClick={() => remove(i)}>
                  <Trash2 />
                </Button>
              )}
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Connection" error={e?.connection?.message}>
                {(id) => (
                  <Select id={id} {...form.register(`sinks.${i}.connection`)}>
                    <option value="">Choose…</option>
                    {connections.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
              <Field label="Target table" hint="Schema-qualified, e.g. public.customers." error={e?.target?.message}>
                {(id) => <Input id={id} className="font-mono" {...form.register(`sinks.${i}.target`)} />}
              </Field>
            </div>
            <label className="flex items-center gap-2 text-sm">
              <Checkbox {...form.register(`sinks.${i}.autoCreate`)} />
              Create the target table if it does not exist
            </label>
            <Field
              label="Deletes"
              hint={
                deleteMode === "SOFT"
                  ? "Deleted rows stay, flagged in the columns below. A row that comes back is un-flagged."
                  : deleteMode === "HARD"
                    ? "Deleted rows are removed from the target."
                    : "Deletes are not applied to the target."
              }
            >
              {(id) => (
                <Select
                  id={id}
                  className="sm:w-72"
                  value={deleteMode}
                  onChange={(ev) => {
                    const mode = ev.target.value as typeof deleteMode;
                    if (mode === "HARD") setHardDeleteFor(i);
                    else form.setValue(`sinks.${i}.deleteMode`, mode);
                  }}
                >
                  <option value="SOFT">Flag as deleted (soft)</option>
                  <option value="HARD">Delete the row (hard)</option>
                  <option value="IGNORE">Ignore deletes</option>
                </Select>
              )}
            </Field>
            {deleteMode === "SOFT" && (
              <details className="text-sm">
                <summary className="cursor-pointer text-muted-foreground">Soft-delete columns</summary>
                <div className="mt-3 grid gap-4 sm:grid-cols-2">
                  <Field label="Deleted-at column" error={e?.softDeleteColumn?.message}>
                    {(id) => <Input id={id} className="font-mono" {...form.register(`sinks.${i}.softDeleteColumn`)} />}
                  </Field>
                  <Field label="Deleted flag column" error={e?.softDeleteFlagColumn?.message}>
                    {(id) => <Input id={id} className="font-mono" {...form.register(`sinks.${i}.softDeleteFlagColumn`)} />}
                  </Field>
                </div>
              </details>
            )}
          </div>
        );
      })}
      <div>
        <Button variant="outline" onClick={() => append(newSink(suggestedTarget))}>
          <Plus aria-hidden /> Add sink
        </Button>
      </div>
      <ConfirmDialog
        open={hardDeleteFor !== null}
        onOpenChange={(o) => !o && setHardDeleteFor(null)}
        title="Use hard deletes?"
        effect="Rows deleted at the source will be permanently deleted from the target table. Soft deletes keep the row and flag it instead, so nothing is lost."
        confirmLabel="Use hard deletes"
        destructive
        onConfirm={() => {
          if (hardDeleteFor !== null) form.setValue(`sinks.${hardDeleteFor}.deleteMode`, "HARD");
          setHardDeleteFor(null);
        }}
      />
    </div>
  );
}

function renderValue(v: unknown) {
  if (v === null || v === undefined) return <span className="text-subtle-foreground">null</span>;
  return typeof v === "object" ? JSON.stringify(v) : String(v);
}

function DryRunStep({ form, preview, saveError }: { form: Form; preview: UseMutationResult<Preview, Error, JobDef>; saveError: unknown }) {
  const { mutate } = preview;
  useEffect(() => mutate(toJobDef(form.getValues())), [form, mutate]);

  return (
    <div className="grid gap-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">Reads the first rows from the source with this configuration. Nothing is written.</p>
        <Button variant="outline" size="sm" loading={preview.isPending} onClick={() => mutate(toJobDef(form.getValues()))}>
          Run dry run again
        </Button>
      </div>
      <ErrorAlert error={saveError} title="Could not save the job" />
      <ErrorAlert error={preview.error} title="The dry run failed" />
      {preview.isPending && <Spinner label="Reading sample rows…" />}
      {preview.data && (
        <>
          <section>
            <h3 className="mb-2 text-sm font-semibold">Column mapping</h3>
            <Table>
              <thead>
                <tr>
                  <Th>Source column</Th>
                  <Th>Target column</Th>
                  <Th>Type</Th>
                  <Th>Key</Th>
                </tr>
              </thead>
              <tbody>
                {preview.data.columns.map((c) => (
                  <tr key={c.source}>
                    <Td className="font-mono text-xs">{c.source}</Td>
                    <Td className="font-mono text-xs">{c.target}</Td>
                    <Td className="text-xs text-muted-foreground">{typeLabel(c.type)}</Td>
                    <Td>{c.key && <Check className="size-4 text-good-text" aria-label="Key" />}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </section>
          <section>
            <h3 className="mb-2 text-sm font-semibold">Sample rows ({preview.data.rows.length})</h3>
            {preview.data.rows.length === 0 ? (
              <p className="text-sm text-muted-foreground">The source returned no rows.</p>
            ) : (
              <Table>
                <thead>
                  <tr>
                    {preview.data.columns.map((c) => (
                      <Th key={c.target} className="font-mono">
                        {c.target}
                      </Th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {preview.data.rows.map((row, r) => (
                    <tr key={r}>
                      {preview.data.columns.map((c) => (
                        <Td key={c.target} className="max-w-48 truncate font-mono text-xs">
                          {renderValue((row as Record<string, unknown>)[c.target])}
                        </Td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </Table>
            )}
          </section>
          <p className="text-sm text-muted-foreground">
            Looks right? Save the job, then start it from its page. Missing target tables are created on the first run.
          </p>
        </>
      )}
    </div>
  );
}
