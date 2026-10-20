import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Link, Navigate, Route, Routes } from "react-router";
import { auth } from "./api";
import { HuntPage } from "./pages/HuntPage";
import { LoginPage } from "./pages/LoginPage";
import { ProjectPage } from "./pages/ProjectPage";
import { ProjectsPage } from "./pages/ProjectsPage";
import "./styles.css";

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
});

function Layout({ children }: { children: ReactNode }) {
  if (!auth.get()) return <Navigate to="/login" replace />;
  return (
    <>
      <header>
        <Link to="/">FlakeHunter</Link>
        <button
          onClick={() => {
            auth.clear();
            location.assign("/login");
          }}
        >
          Log out
        </button>
      </header>
      <main>{children}</main>
    </>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/"
            element={
              <Layout>
                <ProjectsPage />
              </Layout>
            }
          />
          <Route
            path="/projects/:id"
            element={
              <Layout>
                <ProjectPage />
              </Layout>
            }
          />
          <Route
            path="/hunts/:id"
            element={
              <Layout>
                <HuntPage />
              </Layout>
            }
          />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
