import type { FileInfo } from '$lib/types';

/** Открытый просмотр файлов: один на всё приложение (см. FileViewer в оболочке). */
export const viewer = $state<{ files: FileInfo[]; index: number; open: boolean; title: string }>({
	files: [],
	index: 0,
	open: false,
	title: ''
});

/** Открыть файл (и соседние — листать стрелками или свайпом). */
export function openFiles(files: FileInfo[], index = 0, title = '') {
	viewer.files = files;
	viewer.index = Math.max(0, Math.min(index, files.length - 1));
	viewer.title = title;
	viewer.open = true;
}

export function closeViewer() {
	viewer.open = false;
}
