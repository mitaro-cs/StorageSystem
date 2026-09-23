import { ApiError } from './api';
import { enabled as offlineEnabled, stashFile } from './offline/engine';
import type { FileInfo } from './types';

function csrf(): string {
	for (const part of document.cookie.split(';')) {
		const [name, ...rest] = part.trim().split('=');
		if (name === 'gb_csrf' || name === '__Host-gb_csrf') return rest.join('=');
	}
	return '';
}

/**
 * Загрузка файла сырым телом запроса (сервер шифрует поток на лету). XHR — ради прогресса,
 * у fetch его нет.
 */
export function uploadFile(
	file: File,
	onProgress?: (fraction: number) => void,
	signal?: AbortSignal
): Promise<FileInfo> {
	// Без сети файл ждёт на устройстве и уйдёт вместе с публикацией.
	if (!navigator.onLine && offlineEnabled())
		return stashFile(file).then((f) => (onProgress?.(1), f));
	return new Promise((resolve, reject) => {
		const xhr = new XMLHttpRequest();
		xhr.open('POST', '/api/files');
		xhr.setRequestHeader('X-CSRF-Token', csrf());
		xhr.setRequestHeader('X-File-Name', encodeURIComponent(file.name));
		xhr.setRequestHeader('Content-Type', 'application/octet-stream');
		xhr.upload.onprogress = (e) => e.lengthComputable && onProgress?.(e.loaded / e.total);
		xhr.onload = () => {
			let data: { error?: string; message?: string } & Partial<FileInfo> = {};
			try {
				data = JSON.parse(xhr.responseText);
			} catch {
				/* пустой ответ */
			}
			if (xhr.status >= 200 && xhr.status < 300) resolve(data as FileInfo);
			else
				reject(
					new ApiError(
						xhr.status,
						data.error ?? 'error',
						data.message ?? (xhr.status === 413 ? 'Файл слишком большой' : `Ошибка ${xhr.status}`)
					)
				);
		};
		xhr.onerror = () => reject(new ApiError(0, 'network', 'Нет связи с сервером'));
		signal?.addEventListener('abort', () => xhr.abort());
		xhr.send(file);
	});
}
