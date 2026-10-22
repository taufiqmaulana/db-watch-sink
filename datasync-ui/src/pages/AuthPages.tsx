import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Database } from "lucide-react";
import type { ReactNode } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { api, call } from "@/api/client";
import { keys } from "@/api/queries";
import { ChangePasswordForm } from "@/components/ChangePasswordForm";
import { ErrorAlert } from "@/components/ErrorAlert";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/form";
import { Card } from "@/components/ui/layout";

function AuthShell({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-12">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center justify-center gap-2 text-lg font-semibold">
          <Database className="size-5 text-primary" aria-hidden />
          DataSync
        </div>
        <Card className="p-6">
          <h1 className="text-base font-semibold">{title}</h1>
          <p className="mt-1 mb-5 text-sm text-muted-foreground">{description}</p>
          {children}
        </Card>
      </div>
    </main>
  );
}

const loginSchema = z.object({
  username: z.string().trim().min(1, "Enter your username"),
  password: z.string().min(1, "Enter your password"),
});

export function LoginPage() {
  const queryClient = useQueryClient();
  const form = useForm({ resolver: zodResolver(loginSchema), defaultValues: { username: "", password: "" } });
  const login = useMutation({
    mutationFn: (body: z.infer<typeof loginSchema>) => call(api.POST("/api/v1/auth/login", { body })),
    onSuccess: (me) => queryClient.setQueryData(keys.me, me),
  });
  const { errors } = form.formState;

  return (
    <AuthShell title="Sign in" description="Manage and monitor your sync jobs.">
      <form className="grid gap-4" onSubmit={form.handleSubmit((v) => login.mutate(v))}>
        <ErrorAlert error={login.error} />
        <Field label="Username" error={errors.username?.message}>
          {(id) => <Input id={id} autoComplete="username" autoFocus {...form.register("username")} />}
        </Field>
        <Field label="Password" error={errors.password?.message}>
          {(id) => <Input id={id} type="password" autoComplete="current-password" {...form.register("password")} />}
        </Field>
        <Button type="submit" loading={login.isPending}>
          Sign in
        </Button>
      </form>
    </AuthShell>
  );
}

export function ForcedPasswordChangePage() {
  return (
    <AuthShell title="Choose a new password" description="Your password was set by an administrator. Choose your own to continue.">
      <ChangePasswordForm submitLabel="Save and continue" />
    </AuthShell>
  );
}
