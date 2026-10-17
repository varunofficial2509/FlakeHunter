// Used by the local commit-msg hook (pre-commit, FH S0). Mirrors .github/workflows/pr-title.yml.
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'scope-enum': [2, 'always', [
      'gateway', 'project', 'orchestrator', 'aggregator', 'agent', 'runner', 'web',
      'events', 'helm', 'k8s', 'infra', 'ci', 'docs', 'deps', 'repo',
    ]],
  },
};
