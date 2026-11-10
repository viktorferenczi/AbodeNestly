// Codes are stable snake_case strings (node-backend skill); clients switch on them.
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
