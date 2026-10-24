-- The runners run Maven only, and nothing hunts pull requests automatically.
ALTER TABLE projects DROP COLUMN auto_hunt_on_pr;
ALTER TABLE projects DROP CONSTRAINT projects_build_tool_check;
ALTER TABLE projects ADD CONSTRAINT projects_build_tool_check CHECK (build_tool = 'MAVEN');
