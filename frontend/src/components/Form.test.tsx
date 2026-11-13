import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import { Form } from "./Form";
describe("forms", () => {
  it("validates email and password before submission", async () => {
    const submit = vi.fn();
    const user = userEvent.setup();
    render(
      <Form
        fields={[
          { name: "email", label: "Email", required: true, type: "email" },
          {
            name: "password",
            label: "Password",
            required: true,
            type: "password",
            min: 12,
          },
        ]}
        onSubmit={submit}
      />,
    );
    await user.type(screen.getByLabelText(/Email/), "invalid");
    await user.type(screen.getByLabelText(/Password/), "short");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(
      await screen.findByText("Enter a valid email address"),
    ).toBeInTheDocument();
    expect(screen.getByText("Use at least 12 characters")).toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
  });
  it("submits valid values and surfaces server failures", async () => {
    const submit = vi
      .fn()
      .mockRejectedValue(new Error("This project key is already used"));
    const user = userEvent.setup();
    render(
      <Form
        fields={[{ name: "name", label: "Name", required: true }]}
        onSubmit={submit}
      />,
    );
    await user.type(screen.getByLabelText(/Name/), "Project");
    await user.click(screen.getByRole("button"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This project key is already used",
    );
    expect(submit).toHaveBeenCalledWith({ name: "Project" });
  });
});
