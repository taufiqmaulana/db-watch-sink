import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { api, call, type Role, type UserView } from "@/api/client";
import { keys, useAccess, useMe, useUsers } from "@/api/queries";
import { AuditTable } from "@/components/AuditTable";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { ErrorAlert } from "@/components/ErrorAlert";
import { toast } from "@/components/Toaster";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/form";
import { Card, CardHeader, PageHeader, Spinner, Table, Td, Th } from "@/components/ui/layout";
import { Tabs } from "@/components/ui/tabs";
import { formatDateTime } from "@/lib/format";

const ROLES: Record<Role, string> = {
  VIEWER: "Viewer: read only",
  OPERATOR: "Operator: run, pause and review jobs",
  ADMIN: "Admin: everything, including connections, jobs and users",
};

const MIN_PASSWORD = 10;

const userSchema = z.object({
  username: z.string().trim().min(1, "Username is required"),
  role: z.enum(["VIEWER", "OPERATOR", "ADMIN"]),
  /** Required for a new user; optional reset when editing. */
  password: z.string(),
});
type UserValues = z.infer<typeof userSchema>;

function UserDialog({ user, open, onOpenChange }: { user?: UserView; open: boolean; onOpenChange: (o: boolean) => void }) {
  const queryClient = useQueryClient();
  const form = useForm<UserValues>({
    resolver: zodResolver(
      userSchema.refine((v) => (user && !v.password) || v.password.length >= MIN_PASSWORD, {
        path: ["password"],
        message: `Use at least ${MIN_PASSWORD} characters`,
      }),
    ),
    values: { username: user?.username ?? "", role: user?.role ?? "VIEWER", password: "" },
  });
  const save = useMutation({
    mutationFn: (v: UserValues) =>
      user
        ? call(api.PUT("/api/v1/users/{id}", { params: { path: { id: user.id } }, body: { role: v.role, password: v.password } }))
        : call(api.POST("/api/v1/users", { body: v })),
    onSuccess: (saved) => {
      queryClient.invalidateQueries({ queryKey: keys.users });
      toast.success(`Saved ${saved.username}`);
      onOpenChange(false);
    },
  });
  const { errors } = form.formState;

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) save.reset();
        onOpenChange(o);
      }}
      title={user ? `Edit ${user.username}` : "New user"}
      description="Passwords set here are temporary: the user chooses their own at first sign-in."
      footer={
        <>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" form="user-form" loading={save.isPending}>
            Save
          </Button>
        </>
      }
    >
      <form id="user-form" className="grid gap-4" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
        <ErrorAlert error={save.error} />
        <Field label="Username" error={errors.username?.message}>
          {(id) => <Input id={id} autoComplete="off" disabled={Boolean(user)} {...form.register("username")} />}
        </Field>
        <Field label="Role">
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
        <Field
          label={user ? "Reset password" : "Temporary password"}
          hint={user ? "Leave empty to keep the current password." : `At least ${MIN_PASSWORD} characters.`}
          error={errors.password?.message}
        >
          {(id) => <Input id={id} type="password" autoComplete="new-password" {...form.register("password")} />}
        </Field>
      </form>
    </Dialog>
  );
}

function UsersPanel() {
  const users = useUsers();
  const me = useMe().data;
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<UserView | "new" | null>(null);
  const [deleting, setDeleting] = useState<UserView | null>(null);
  const remove = useMutation({
    mutationFn: (u: UserView) => call(api.DELETE("/api/v1/users/{id}", { params: { path: { id: u.id } } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.users }),
    onError: toast.error,
    onSettled: () => setDeleting(null),
  });

  return (
    <Card>
      <CardHeader
        title="Users"
        description="Local accounts and their roles."
        actions={
          <Button size="sm" onClick={() => setEditing("new")}>
            <Plus aria-hidden /> New user
          </Button>
        }
      />
      {users.isPending ? (
        <Spinner />
      ) : users.error ? (
        <div className="p-5">
          <ErrorAlert error={users.error} />
        </div>
      ) : (
        <Table>
          <thead>
            <tr>
              <Th>Username</Th>
              <Th>Role</Th>
              <Th>Created</Th>
              <Th className="w-0" />
            </tr>
          </thead>
          <tbody>
            {users.data.map((u) => (
              <tr key={u.id}>
                <Td className="font-medium">
                  {u.username}
                  {u.mustChangePassword && <span className="ml-2 text-xs font-normal text-muted-foreground">password change pending</span>}
                </Td>
                <Td className="capitalize">{u.role.toLowerCase()}</Td>
                <Td className="whitespace-nowrap">{formatDateTime(u.createdAt)}</Td>
                <Td>
                  <div className="flex justify-end gap-1">
                    <Button size="icon" variant="ghost" aria-label={`Edit ${u.username}`} onClick={() => setEditing(u)}>
                      <Pencil />
                    </Button>
                    <Button
                      size="icon"
                      variant="ghost"
                      aria-label={`Delete ${u.username}`}
                      disabled={u.username === me?.username}
                      onClick={() => setDeleting(u)}
                    >
                      <Trash2 />
                    </Button>
                  </div>
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
      <UserDialog open={editing !== null} user={editing === "new" || editing === null ? undefined : editing} onOpenChange={(o) => !o && setEditing(null)} />
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        title={`Delete ${deleting?.username}?`}
        effect="The account is removed and signed out everywhere. Its entries in the audit log are kept."
        confirmLabel="Delete user"
        destructive
        loading={remove.isPending}
        onConfirm={() => deleting && remove.mutate(deleting)}
      />
    </Card>
  );
}

export function SettingsPage() {
  const { canAdmin } = useAccess();
  const audit = {
    value: "audit",
    label: "Audit log",
    content: (
      <Card>
        <CardHeader title="Audit log" description="Configuration changes, operator actions and sign-ins." />
        <AuditTable />
      </Card>
    ),
  };
  return (
    <>
      <PageHeader title="Settings" />
      <Tabs tabs={canAdmin ? [{ value: "users", label: "Users", content: <UsersPanel /> }, audit] : [audit]} />
    </>
  );
}
