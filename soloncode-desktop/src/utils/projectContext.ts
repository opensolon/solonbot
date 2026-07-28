interface ProjectReference {
  id: string;
  name: string;
}

export function resolveProjectName(
  projects: readonly ProjectReference[],
  workspacePath?: string | null,
): string | undefined {
  if (!workspacePath) return undefined;
  return projects.find(project => project.id === workspacePath)?.name;
}
