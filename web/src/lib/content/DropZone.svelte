<script lang="ts">
	import { Camera, RotateCw, Upload, X } from '@lucide/svelte';
	import { onMount } from 'svelte';
	import { ApiError } from '$lib/api';
	import { drop, registerDrop, takeWaiting } from '$lib/files/drop.svelte';
	import { uploadFile } from '$lib/upload';
	import { fmtSize } from '$lib/format';
	import { slide } from '$lib/motion';
	import type { FileInfo } from '$lib/types';

	interface Props {
		files: FileInfo[];
		/** Сколько файлов ещё загружается: форма не отправляется, пока они не дойдут. */
		uploading?: number;
		multiple?: boolean;
		max?: number;
		label?: string;
		/** Большие фото уменьшать до 2048 точек перед отправкой (новости: лента грузится быстрее). */
		shrink?: boolean;
		/** Подсказка под «Выберите файл». */
		hint?: string;
	}

	let {
		files = $bindable(),
		uploading = $bindable(0),
		multiple = true,
		max = 10,
		label = 'Файлы',
		shrink = false,
		hint = 'PDF, документы, презентации, картинки, архивы'
	}: Props = $props();

	interface Pending {
		key: number;
		name: string;
		file: File;
		progress: number;
		error?: string;
	}

	let pending = $state<Pending[]>([]);
	// Счётчик для формы: загрузки без ошибки, которые ещё идут.
	const busy = $derived(pending.filter((p) => !p.error).length);
	$effect(() => {
		if (uploading !== busy) uploading = busy;
	});
	let input: HTMLInputElement | undefined = $state();
	let camera: HTMLInputElement | undefined = $state();
	let seq = 0;
	// Кнопка «Сфотографировать» – только там, где есть камера и палец, а не мышь.
	const touch = typeof matchMedia !== 'undefined' && matchMedia('(pointer: coarse)').matches;

	/** Фото с телефона – 4–10 МБ; для новости хватит 2048 точек и JPEG (~300–600 КБ). */
	async function smaller(file: File): Promise<File> {
		if (!shrink || !/^image\/(jpeg|png|webp|heic|heif)$/.test(file.type) || file.size < 700_000)
			return file;
		try {
			const bitmap = await createImageBitmap(file);
			const k = Math.min(1, 2048 / Math.max(bitmap.width, bitmap.height));
			const canvas = document.createElement('canvas');
			canvas.width = Math.round(bitmap.width * k);
			canvas.height = Math.round(bitmap.height * k);
			canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
			bitmap.close();
			const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/jpeg', 0.86));
			if (!blob || blob.size >= file.size) return file;
			return new File([blob], file.name.replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg' });
		} catch {
			return file;
		}
	}

	/** Скриншот из буфера приходит как «image.png» – даём понятное имя с датой. */
	function named(f: File): File {
		if (f.name && f.name !== 'image.png') return f;
		const stamp = new Date().toLocaleString('ru-RU').replace(/[/:]/g, '.');
		return new File([f], `Скриншот ${stamp}.png`, { type: f.type || 'image/png' });
	}

	/** Сбой связи (туннель, мобильная сеть) – повторяем сами, а не сразу показываем ошибку. */
	const transient = (e: unknown) =>
		e instanceof ApiError && (e.status === 0 || (e.status >= 502 && e.status <= 504));

	async function send(item: Pending) {
		item.error = undefined;
		item.progress = 0;
		try {
			item.file = await smaller(item.file);
			let info;
			for (let attempt = 0; ; attempt++) {
				try {
					info = await uploadFile(item.file, (f) => (item.progress = f));
					break;
				} catch (e) {
					if (attempt >= 2 || !transient(e)) throw e;
					item.progress = 0;
					await new Promise((r) => setTimeout(r, 1500 * (attempt + 1)));
				}
			}
			files = [...files, info];
			pending = pending.filter((x) => x.key !== item.key);
		} catch (e) {
			item.error = e instanceof Error ? e.message : 'Ошибка загрузки';
		}
	}

	async function add(list: FileList | File[] | null) {
		if (!list) return;
		const room = Math.max(0, (multiple ? max : 1) - files.length - pending.length);
		for (const file of Array.from(list).map(named).slice(0, room)) {
			pending.push({ key: ++seq, name: file.name || 'файл', file, progress: 0 });
			await send(pending[pending.length - 1]);
			if (!multiple) break;
		}
	}

	// Брошенные в любое место окна и вставленные (Ctrl+V / ⌘V) файлы получает это поле – пока оно
	// последнее открытое (макет, lib/files/drop.svelte.ts); ждавшие его файлы – сразу.
	onMount(() => {
		const off = registerDrop((list) => add(list));
		const waiting = takeWaiting();
		if (waiting.length) add(waiting);
		return off;
	});
</script>

<div class="dz">
	<span class="label">{label}</span>
	<button type="button" class="zone" class:over={drop.over} onclick={() => input?.click()}>
		<Upload size={20} />
		<span><strong>Выберите файл</strong> или перетащите сюда</span>
		<span class="faint small">{hint}{touch ? '' : ' · можно вставить Ctrl+V'}</span>
	</button>
	{#if touch}
		<button type="button" class="shoot" onclick={() => camera?.click()}
			><Camera size={18} /> Сфотографировать</button
		>
		<input
			bind:this={camera}
			type="file"
			accept="image/*"
			capture="environment"
			hidden
			onchange={(e) => add(e.currentTarget.files)}
		/>
	{/if}
	<input
		bind:this={input}
		type="file"
		{multiple}
		hidden
		onchange={(e) => add(e.currentTarget.files)}
	/>

	<!-- Загрузился – строка просто становится готовой: без второй анимации поверх первой. -->
	{#each files as f (f.id)}
		<div class="item" out:slide>
			<span class="name">{f.name}</span>
			<span class="faint small num">{fmtSize(f.size)}</span>
			<button
				type="button"
				class="x"
				aria-label="Убрать {f.name}"
				onclick={() => (files = files.filter((x) => x.id !== f.id))}><X size={15} /></button
			>
		</div>
	{/each}
	{#each pending as p (p.key)}
		<div class="item" in:slide>
			<span class="name">{p.name}</span>
			{#if p.error}
				<span class="error-text small">{p.error}</span>
				<button
					type="button"
					class="x"
					aria-label="Повторить"
					title="Повторить"
					onclick={() => send(p)}><RotateCw size={15} /></button
				>
				<button
					type="button"
					class="x"
					aria-label="Убрать"
					onclick={() => (pending = pending.filter((x) => x.key !== p.key))}><X size={15} /></button
				>
			{:else}
				<span
					class="bar"
					role="progressbar"
					aria-valuenow={Math.round(p.progress * 100)}
					aria-valuemin={0}
					aria-valuemax={100}><span style:width="{p.progress * 100}%"></span></span
				>
			{/if}
		</div>
	{/each}
</div>

<style>
	.dz {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.label {
		font-size: 13px;
		font-weight: 550;
		color: var(--text-2);
	}
	.zone {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		padding: 20px;
		border: 1.5px dashed var(--border-strong);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text-2);
		transition:
			border-color var(--dur) var(--ease),
			background-color var(--dur) var(--ease);
	}
	.zone:hover,
	.zone.over {
		border-color: var(--accent);
		background: var(--accent-soft);
		color: var(--accent);
	}
	.shoot {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 8px;
		height: 44px;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-weight: 600;
	}
	.shoot:active {
		transform: scale(0.98);
	}
	.item {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 10px;
		border-radius: var(--r-s);
		background: var(--surface-2);
	}
	.name {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: 14px;
	}
	.x {
		display: grid;
		place-items: center;
		width: 26px;
		height: 26px;
		border: 0;
		border-radius: 7px;
		background: transparent;
		color: var(--text-3);
	}
	.x:hover {
		background: var(--surface-3);
		color: var(--text);
	}
	.bar {
		width: 120px;
		height: 6px;
		border-radius: 3px;
		background: var(--surface-3);
		overflow: hidden;
	}
	.bar span {
		display: block;
		height: 100%;
		background: var(--accent);
		transition: width 120ms linear;
	}
</style>
