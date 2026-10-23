import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { ApiError, auth, post } from "../api";
import { Alert, Button, Field, Icon } from "../ui";

export function LoginPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setPending(true);
    setError("");
    try {
      const { token } = await post<{ token: string }>("/api/auth/login", {
        username,
        password,
      });
      auth.set(token);
      navigate("/");
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 401
          ? "Wrong username or password"
          : String(err),
      );
      setPending(false);
    }
  }

  return (
    <div className="login">
      <div className="card">
        <div className="brand">
          <Icon name="logo" size={22} />
          FlakeHunter
        </div>
        <p className="muted">Sign in to hunt flaky tests.</p>
        <form onSubmit={submit}>
          <Field label="Username">
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
            />
          </Field>
          <Field label="Password">
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              autoFocus
            />
          </Field>
          {error && <Alert>{error}</Alert>}
          <Button type="submit" pending={pending}>
            {pending ? "Signing in…" : "Log in"}
          </Button>
        </form>
      </div>
    </div>
  );
}
