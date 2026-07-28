import { invoke } from '@tauri-apps/api/core';

export interface WorkspaceCheckpoint {
  id: string;
  label: string;
  commit: string;
  createdAt: number;
  changedFiles: number;
}

export const checkpointService = {
  create(workspace: string, label: string): Promise<WorkspaceCheckpoint> {
    return invoke('workspace_checkpoint_create', { workspace, label });
  },
  list(workspace: string): Promise<WorkspaceCheckpoint[]> {
    return invoke('workspace_checkpoint_list', { workspace });
  },
  restore(workspace: string, checkpointId: string): Promise<void> {
    return invoke('workspace_checkpoint_restore', { workspace, checkpointId });
  },
  delete(workspace: string, checkpointId: string): Promise<void> {
    return invoke('workspace_checkpoint_delete', { workspace, checkpointId });
  },
};
