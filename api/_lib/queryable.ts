// The slice of pg.PoolClient that data functions need; lets unit tests pass a fake.
export interface Queryable {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  query(text: string, values?: unknown[]): Promise<{ rows: any[] }>;
}
