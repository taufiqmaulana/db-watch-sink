import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Pencil, Plus, Trash2, XCircle } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { api, call, type ConnectionView, type Schemas } from "@/api/client";
import { keys, useAccess, useConnections } from "@/api/queries";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { ErrorAlert } from "@/components/ErrorAlert";
import { toast } from "@/components/Toaster";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/form";
import { Card, Empty, PageHeader, Spinner, Table, Td, Th } from "@/components/ui/layout";

export const KINDS = {
  "jdbc-oracle": { label: "Oracle", url: "jdbc:oracle:thin:@//host:1521/SERVICE" },
  "jdbc-postgres": { label: "PostgreSQL", url: "jdbc:postgresql://host:5432/database" },
} as const;

const ROLES: Record<Schemas["ConnectionRole"], string> = { SOURCE: "Source", SINK: "Sink", BOTH: "Source and sink" };

const schema = z.object({
  name: z.string().trim().min(1, "Name is required"),
  kind: z.enum(["jdbc-oracle", "jdbc-postgres"]),
  role: z.enum(["SOURCE", "SINK", "BOTH"]),
  url: z.string().trim().min(1, "JDBC URL is required"),
  username: z.string().trim(),
  password: z.string(),
  maxConcurrency: z.number({ error: "Enter a number" }).int().min(1, "At least 1").max(32, "At most 32"),
});
type Values = z.infer<typeof schema>;

function ConnectionDialog({ connection, open, onOpenChange }: { connection?: ConnectionView; open: boolean; onOpenChange: (o: boolean) => void }) {
  const queryClient = useQueryClient();
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    values: {
      name: connection?.name ?? "",
      kind: (connection?.kind as Values["kind"]) ?? "jdbc-postgres",
      role: connection?.role ?? "SOURCE",
      url: connection?.url ?? "",
      username: connection?.username ?? "",
      password: "",
      maxConcurrency: connection?.maxConcurrency ?? 2,
    },
  });
  const save = useMutation({
    mutationFn: (body: Values) =>
      connection
        ? call(api.PUT("/api/v1/connections/{id}", { params: { path: { id: connection.id } }, body }))
        : call(api.POST("/api/v1/connections", { body })),
    onSuccess: (saved) => {
      queryClient.invalidateQueries({ queryKey: keys.connections });
      toast.success(`Saved ${saved.name}. Use Test to check it connects.`);
      onOpenChange(false);
    },
  });
  const { errors } = form.formState;
  const kind = form.watch("kind");

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) save.reset();
        onOpenChange(o);
      }}
      title={connection ? `Edit ${connection.name}` : "New connection"}
      description="Sources only need a read-only account with SELECT."
      footer={
        <>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" form="connection-form" loading={save.isPending}>
            Save
          </Button>
        </>
      }
    >
      <form id="connection-form" className="grid gap-4" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
        <ErrorAlert error={save.error} />
        <Field label="Name" error={errors.name?.message}>
          {(id) => <Input id={id} placeholder="oracle_prod" {...form.register("name")} />}
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Database">
            {(id) => (
              <Select id={id} {...form.register("kind")}>
                {Object.entries(KINDS).map(([value, k]) => (
                  <option key={value} value={value}>
                    {k.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Used as">
            {(id) => (
              <Select id={id} {...form.register("role")}>
                {Object.entries(ROLES).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
        <Field label="JDBC URL" error={errors.url?.message}>
          {(id) => <Input id={id} placeholder={KINDS[kind].url} className="font-mono" {...form.register("url")} />}
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Username">
            {(id) => <Input id={id} autoComplete="off" {...form.register("username")} />}
          </Field>
          <Field label="Password" hint={connection?.hasPassword ? "Leave empty to keep the stored password." : "Stored encrypted, never shown again."}>
            {(id) => <Input id={id} type="password" autoComplete="new-password" {...form.register("password")} />}
          </Field>
        </div>
        <Field label="Max concurrent queries" hint="Caps the load this service puts on the database." error={errors.maxConcurrency?.message}>
          {(id) => <Input id={id} type="number" min={1} max={32} className="w-28" {...form.register("maxConcurrency", { valueAsNumber: true })} />}
        </Field>
      </form>
    </Dialog>
  );
}

type TestState = { ok: boolean; message: string; detail?: string | null };

export function ConnectionsPage() {
  const connections = useConnections();
  const { canOperate, canAdmin } = useAccess();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<ConnectionView | "new" | null>(null);
  const [deleting, setDeleting] = useState<ConnectionView | null>(null);
  const [tests, setTests] = useState<Record<string, TestState>>({});

  const test = useMutation({
    mutationFn: (c: ConnectionView) => call(api.POST("/api/v1/connections/{id}/test", { params: { path: { id: c.id } } })),
    onSuccess: (result, c) => setTests((t) => ({ ...t, [c.id]: result })),
    onError: (e, c) => setTests((t) => ({ ...t, [c.id]: { ok: false, message: e.message } })),
  });
  const remove = useMutation({
    mutationFn: (c: ConnectionView) => call(api.DELETE("/api/v1/connections/{id}", { params: { path: { id: c.id } } })),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.connections });
      setDeleting(null);
    },
    onError: (e) => {
      setDeleting(null);
      toast.error(e);
    },
  });

  return (
    <>
      <PageHeader
        title="Connections"
        description="Databases that jobs read from and write to."
        actions={
          canAdmin && (
            <Button onClick={() => setEditing("new")}>
              <Plus aria-hidden /> New connection
            </Button>
          )
        }
      />
      <ErrorAlert error={connections.error} />
      <Card>
        {connections.isPending ? (
          <Spinner />
        ) : !connections.data?.length ? (
          <Empty title="No connections yet">{canAdmin && "Add the source and target databases first."}</Empty>
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Name</Th>
                <Th>Database</Th>
                <Th>Used as</Th>
                <Th>URL</Th>
                <Th>User</Th>
                <Th className="w-0" />
              </tr>
            </thead>
            <tbody>
              {connections.data.map((c) => {
                const result = tests[c.id];
                return (
                  <tr key={c.id} className="hover:bg-muted/50">
                    <Td className="font-medium">{c.name}</Td>
                    <Td>{KINDS[c.kind as keyof typeof KINDS]?.label ?? c.kind}</Td>
                    <Td>{ROLES[c.role]}</Td>
                    <Td className="max-w-xs">
                      <span className="block truncate font-mono text-xs" title={c.url}>
                        {c.url}
                      </span>
                      {result && (
                        <span className={`mt-1 flex items-start gap-1 text-xs ${result.ok ? "text-good-text" : "text-critical-text"}`}>
                          {result.ok ? <CheckCircle2 className="size-3.5 shrink-0" aria-hidden /> : <XCircle className="size-3.5 shrink-0" aria-hidden />}
                          <span className="break-words whitespace-normal">
                            {result.message}
                            {result.detail && `: ${result.detail}`}
                          </span>
                        </span>
                      )}
                    </Td>
                    <Td>{c.username ?? "—"}</Td>
                    <Td>
                      <div className="flex justify-end gap-1">
                        {canOperate && (
                          <Button size="sm" variant="outline" loading={test.isPending && test.variables?.id === c.id} onClick={() => test.mutate(c)}>
                            Test
                          </Button>
                        )}
                        {canAdmin && (
                          <>
                            <Button size="icon" variant="ghost" aria-label={`Edit ${c.name}`} onClick={() => setEditing(c)}>
                              <Pencil />
                            </Button>
                            <Button size="icon" variant="destructive-ghost" aria-label={`Delete ${c.name}`} onClick={() => setDeleting(c)}>
                              <Trash2 />
                            </Button>
                          </>
                        )}
                      </div>
                    </Td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
        )}
      </Card>
      <ConnectionDialog
        open={editing !== null}
        connection={editing === "new" || editing === null ? undefined : editing}
        onOpenChange={(o) => !o && setEditing(null)}
      />
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        title={`Delete ${deleting?.name}?`}
        effect="The connection and its stored password are removed. Connections still used by a job cannot be deleted."
        confirmLabel="Delete connection"
        destructive
        loading={remove.isPending}
        onConfirm={() => deleting && remove.mutate(deleting)}
      />
    </>
  );
}
