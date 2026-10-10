/** Мониторинг хоста (1.0.2) – ответ GET /api/admin/monitor. */
export interface MonitorPoint {
	t: number;
	requests: number;
	errors: number;
	p50: number | null;
	p95: number | null;
	/** Средняя задержка проверки адреса сайта снаружи, мс. */
	rtt: number | null;
	/** Доля удачных проверок адреса сайта (null – не проверяли). */
	reach: number | null;
	online: number;
	/** Доля минут, когда сервер работал. */
	up: number;
}

export interface MonitorView {
	now: {
		online: number;
		requestsPerMin: number;
		p50: number | null;
		p95: number | null;
		errorsHour: number;
		rtt: number | null;
		reachable: boolean | null;
		probedAt: number | null;
		url: string | null;
	};
	uptimeDay: number | null;
	uptimeHour: number | null;
	startedAt: number;
	memory: { used: number; max: number };
	step: number;
	points: MonitorPoint[];
}
