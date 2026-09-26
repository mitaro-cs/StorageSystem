/** Итог обещания: значение или ошибка. */
export type Settled<T> = { ok: true; value: T } | { ok: false; error: unknown };

/**
 * Ждёт обещание не дольше ms. null — не успело; само обещание при этом продолжает работать, и его
 * результат можно дождаться позже (так медленный ответ сети обновляет уже показанную копию).
 */
export function within<T>(p: Promise<T>, ms: number): Promise<Settled<T> | null> {
	return new Promise((resolve) => {
		const timer = setTimeout(() => resolve(null), ms);
		p.then(
			(value) => {
				clearTimeout(timer);
				resolve({ ok: true, value });
			},
			(error: unknown) => {
				clearTimeout(timer);
				resolve({ ok: false, error });
			}
		);
	});
}
