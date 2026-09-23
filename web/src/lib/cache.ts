/**
 * Кеш ответов в памяти вкладки: при возврате «назад» список рисуется сразу из кеша, поэтому
 * SvelteKit восстанавливает позицию прокрутки; свежие данные подгружаются следом.
 */
const store = new Map<string, unknown>();

export function peek<T>(key: string): T | undefined {
	return store.get(key) as T | undefined;
}

export function put<T>(key: string, value: T): T {
	store.set(key, value);
	if (store.size > 50) store.delete(store.keys().next().value!);
	return value;
}

export function clearCache() {
	store.clear();
}
