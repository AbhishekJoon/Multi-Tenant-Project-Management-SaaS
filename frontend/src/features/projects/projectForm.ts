import type { Field } from "../../components/Form";
export const projectFields: Field[] = [
  { name: "name", label: "Project name", required: true, max: 150 },
  {
    name: "projectKey",
    label: "Project key (2–10 uppercase letters/numbers)",
    required: true,
    max: 10,
  },
  { name: "description", label: "Description", type: "textarea", max: 4000 },
  {
    name: "status",
    label: "Status",
    options: ["PLANNED", "ACTIVE", "ON_HOLD", "COMPLETED"],
  },
  {
    name: "visibility",
    label: "Visibility",
    options: ["ORGANIZATION", "PRIVATE"],
  },
  {
    name: "priority",
    label: "Priority",
    options: ["MEDIUM", "LOW", "HIGH", "URGENT"],
  },
  { name: "startDate", label: "Start date", type: "date" },
  { name: "dueDate", label: "Due date", type: "date" },
];
export function projectPayload(values: Record<string, string>) {
  return {
    ...values,
    startDate: values.startDate || null,
    dueDate: values.dueDate || null,
  };
}
