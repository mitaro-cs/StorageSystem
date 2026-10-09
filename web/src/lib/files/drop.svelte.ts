/**
 * Файлы, брошенные в любое место окна или вставленные из буфера (0.9.8). Получает их последнее
 * открытое поле вложений (DropZone – регистрируется при показе); если такого нет – файлы ждут,
 * а макет открывает «Загрузить файл»: первое поле вложений, которое появится, заберёт их.
 */
type Target = (files: File[]) => void;

const targets: Target[] = [];
export const drop = $state({ over: false });
let waiting: { files: File[]; at: number } | null = null;

export function registerDrop(t: Target): () => void {
	targets.push(t);
	return () => {
		const i = targets.lastIndexOf(t);
		if (i >= 0) targets.splice(i, 1);
	};
}

/** Отдать файлы открытому полю; false – поля нет, файлы ждут (минуту). */
export function deliver(files: File[]): boolean {
	const t = targets.at(-1);
	if (t) {
		t(files);
		return true;
	}
	waiting = { files, at: Date.now() };
	return false;
}

export function takeWaiting(): File[] {
	const w = waiting;
	waiting = null;
	return w && Date.now() - w.at < 60_000 ? w.files : [];
}
