import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
export interface Field {
  name: string;
  label: string;
  type?: string;
  required?: boolean;
  min?: number;
  max?: number;
  options?: (string | { value: string; label: string })[];
  placeholder?: string;
}
export function Form({
  fields,
  initial = {},
  submit = "Save changes",
  onSubmit,
}: {
  fields: Field[];
  initial?: Record<string, string>;
  submit?: string;
  onSubmit: (values: Record<string, string>) => Promise<unknown>;
}) {
  const shape: Record<string, z.ZodString> = {};
  fields.forEach((f) => {
    let s = z.string().max(f.max ?? 2000);
    if (f.required)
      s = s.min(
        f.min ?? 1,
        f.type === "password"
          ? `Use at least ${f.min ?? 1} characters`
          : `${f.label} is required`,
      );
    if (f.type === "email") s = s.email("Enter a valid email address");
    shape[f.name] = s;
  });
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<Record<string, string>>({
    resolver: zodResolver(z.object(shape)),
    defaultValues: Object.fromEntries(
      fields.map((f) => [
        f.name,
        initial[f.name] ??
          (typeof f.options?.[0] === "string"
            ? f.options[0]
            : (f.options?.[0]?.value ?? "")),
      ]),
    ),
  });
  const [error, setError] = useState("");
  return (
    <form
      noValidate
      onSubmit={handleSubmit(async (values) => {
        setError("");
        try {
          await onSubmit(values);
        } catch (e) {
          setError(e instanceof Error ? e.message : "Unable to save");
        }
      })}
      className="form-stack"
    >
      {fields.map((f) => (
        <div className="field" key={f.name}>
          <label htmlFor={f.name}>
            {f.label}
            {f.required && <span aria-hidden="true"> *</span>}
          </label>
          {f.options ? (
            <select
              id={f.name}
              {...register(f.name)}
              aria-invalid={!!errors[f.name]}
            >
              {f.options.map((option) => {
                const value =
                  typeof option === "string" ? option : option.value;
                return (
                  <option key={value} value={value}>
                    {typeof option === "string"
                      ? option.replaceAll("_", " ")
                      : option.label}
                  </option>
                );
              })}
            </select>
          ) : f.type === "textarea" ? (
            <textarea
              id={f.name}
              {...register(f.name)}
              rows={4}
              maxLength={f.max ?? 2000}
            />
          ) : (
            <input
              id={f.name}
              type={f.type ?? "text"}
              placeholder={f.placeholder}
              {...register(f.name)}
              maxLength={f.max ?? 2000}
              autoComplete={
                f.type === "password"
                  ? f.name === "currentPassword"
                    ? "current-password"
                    : "new-password"
                  : f.type === "email"
                    ? "email"
                    : undefined
              }
              aria-invalid={!!errors[f.name]}
              aria-describedby={errors[f.name] ? `${f.name}-error` : undefined}
            />
          )}
          {errors[f.name] && (
            <small className="error-text" id={`${f.name}-error`}>
              {errors[f.name]?.message}
            </small>
          )}
        </div>
      ))}
      {error && (
        <div role="alert" className="error-banner">
          {error}
        </div>
      )}
      <button type="submit" className="button primary" disabled={isSubmitting}>
        {isSubmitting ? "Saving…" : submit}
      </button>
    </form>
  );
}
