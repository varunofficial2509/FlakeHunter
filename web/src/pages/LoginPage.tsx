import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { ApiError, auth, post } from "../api";

export function LoginPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");

  async function submit(e: FormEvent) {
    e.preventDefault();
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
    }
  }

  return (
    <main>
      <h1>FlakeHunter</h1>
      <form onSubmit={submit}>
        <label>
          Username
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            autoFocus
          />
        </label>
        {error && <p role="alert">{error}</p>}
        <button type="submit">Log in</button>
      </form>
    </main>
  );
}
