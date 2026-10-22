export interface SwbPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
