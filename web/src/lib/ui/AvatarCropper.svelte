<script lang="ts">
	import { ApiError } from '$lib/api';
	import Modal from './Modal.svelte';
	import Button from './Button.svelte';

	interface Props {
		open: boolean;
		/** Куда отправить PUT с картинкой: /api/me/avatar и т.п. */
		endpoint: string;
		title?: string;
		ondone: (avatar: string) => void;
	}

	let { open = $bindable(), endpoint, title = 'Аватар', ondone }: Props = $props();

	const VIEW = 280;
	const OUT = 512;

	let bitmap = $state<ImageBitmap | null>(null);
	let zoom = $state(1);
	let x = $state(0);
	let y = $state(0);
	let busy = $state(false);
	let error = $state('');
	let canvas: HTMLCanvasElement | undefined = $state();
	let input: HTMLInputElement | undefined = $state();
	let drag: { px: number; py: number; x: number; y: number } | null = null;

	/** Масштаб, при котором картинка закрывает круг целиком. */
	const base = $derived(bitmap ? VIEW / Math.min(bitmap.width, bitmap.height) : 1);

	$effect(() => {
		if (!open) {
			bitmap?.close();
			bitmap = null;
			error = '';
		}
	});

	$effect(() => {
		if (!canvas || !bitmap) return;
		const ctx = canvas.getContext('2d')!;
		const s = base * zoom;
		ctx.clearRect(0, 0, VIEW, VIEW);
		ctx.drawImage(
			bitmap,
			VIEW / 2 - (bitmap.width * s) / 2 + x,
			VIEW / 2 - (bitmap.height * s) / 2 + y,
			bitmap.width * s,
			bitmap.height * s
		);
	});

	function clamp() {
		if (!bitmap) return;
		const s = base * zoom;
		const maxX = Math.max(0, (bitmap.width * s - VIEW) / 2);
		const maxY = Math.max(0, (bitmap.height * s - VIEW) / 2);
		x = Math.min(maxX, Math.max(-maxX, x));
		y = Math.min(maxY, Math.max(-maxY, y));
	}

	async function pick(file: File | undefined) {
		if (!file) return;
		error = '';
		if (!/^image\/(png|jpeg|webp)$/.test(file.type)) {
			error = 'Нужна картинка PNG, JPEG или WebP';
			return;
		}
		if (file.size > 5 * 1024 * 1024) {
			error = 'Картинка больше 5 МБ';
			return;
		}
		try {
			bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
			zoom = 1;
			x = y = 0;
		} catch {
			error = 'Не удалось открыть картинку';
		}
	}

	function down(e: PointerEvent) {
		(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
		drag = { px: e.clientX, py: e.clientY, x, y };
	}
	function move(e: PointerEvent) {
		if (!drag) return;
		x = drag.x + e.clientX - drag.px;
		y = drag.y + e.clientY - drag.py;
		clamp();
	}
	function wheel(e: WheelEvent) {
		e.preventDefault();
		zoom = Math.min(4, Math.max(1, zoom - e.deltaY * 0.002));
		clamp();
	}
	function key(e: KeyboardEvent) {
		const step = 12;
		if (e.key === 'ArrowLeft') x += step;
		else if (e.key === 'ArrowRight') x -= step;
		else if (e.key === 'ArrowUp') y += step;
		else if (e.key === 'ArrowDown') y -= step;
		else if (e.key === '+' || e.key === '=') zoom = Math.min(4, zoom + 0.1);
		else if (e.key === '-') zoom = Math.max(1, zoom - 0.1);
		else return;
		e.preventDefault();
		clamp();
	}

	async function save() {
		if (!bitmap) return;
		busy = true;
		error = '';
		try {
			const out = document.createElement('canvas');
			out.width = out.height = OUT;
			const ctx = out.getContext('2d')!;
			const k = OUT / VIEW;
			const s = base * zoom * k;
			ctx.fillStyle = '#fff';
			ctx.fillRect(0, 0, OUT, OUT);
			ctx.imageSmoothingQuality = 'high';
			ctx.drawImage(
				bitmap,
				OUT / 2 - (bitmap.width * s) / 2 + x * k,
				OUT / 2 - (bitmap.height * s) / 2 + y * k,
				bitmap.width * s,
				bitmap.height * s
			);
			const blob = await new Promise<Blob>((res, rej) =>
				out.toBlob((b) => (b ? res(b) : rej(new Error('canvas'))), 'image/jpeg', 0.92)
			);
			const csrf = document.cookie.match(/(?:^|;\s*)(?:__Host-)?gb_csrf=([^;]+)/)?.[1] ?? '';
			const r = await fetch(endpoint, {
				method: 'PUT',
				body: blob,
				headers: { 'X-CSRF-Token': csrf, 'Content-Type': 'application/octet-stream' }
			});
			const data = await r.json();
			if (!r.ok) throw new ApiError(r.status, data.error, data.message);
			open = false;
			ondone(data.avatar);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}
</script>

<Modal bind:open {title}>
	<div class="crop">
		{#if bitmap}
			<div
				class="view"
				role="slider"
				tabindex="0"
				aria-label="Положение картинки: стрелки — сдвиг, плюс и минус — масштаб"
				aria-valuenow={Math.round(zoom * 100)}
				onpointerdown={down}
				onpointermove={move}
				onpointerup={() => (drag = null)}
				onwheel={wheel}
				onkeydown={key}
			>
				<canvas bind:this={canvas} width={VIEW} height={VIEW}></canvas>
				<div class="mask" aria-hidden="true"></div>
			</div>
			<label class="zoom">
				<span class="sr-only">Масштаб</span>
				<input type="range" min="1" max="4" step="0.01" bind:value={zoom} oninput={clamp} />
			</label>
			<p class="faint small">
				Перетащите картинку, чтобы выбрать область. Метаданные фото (место съёмки, модель телефона)
				не сохраняются.
			</p>
		{:else}
			<button type="button" class="pick" onclick={() => input?.click()}>
				<strong>Выберите картинку</strong>
				<span class="faint small">PNG, JPEG или WebP до 5 МБ</span>
			</button>
		{/if}
		<input
			bind:this={input}
			type="file"
			accept="image/png,image/jpeg,image/webp"
			hidden
			onchange={(e) => pick(e.currentTarget.files?.[0])}
		/>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</div>
	{#snippet footer()}
		{#if bitmap}<Button variant="ghost" onclick={() => input?.click()}>Другая</Button>{/if}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" onclick={save} loading={busy} disabled={!bitmap}>Сохранить</Button>
	{/snippet}
</Modal>

<style>
	.crop {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s3);
	}
	.view {
		position: relative;
		width: 280px;
		height: 280px;
		border-radius: var(--r);
		overflow: hidden;
		background: var(--surface-2);
		cursor: grab;
		touch-action: none;
	}
	.view:active {
		cursor: grabbing;
	}
	canvas {
		display: block;
	}
	.mask {
		position: absolute;
		inset: 0;
		border-radius: 50%;
		box-shadow: 0 0 0 999px rgb(0 0 0 / 0.45);
		pointer-events: none;
		outline: 2px solid rgb(255 255 255 / 0.8);
		outline-offset: -2px;
	}
	.zoom input {
		width: 240px;
		accent-color: var(--accent);
	}
	.pick {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		width: 100%;
		padding: var(--s6) var(--s4);
		border: 1.5px dashed var(--border-strong);
		border-radius: var(--r);
		background: var(--surface);
	}
	.pick:hover {
		border-color: var(--accent);
		background: var(--accent-soft);
	}
	p {
		text-align: center;
		max-width: 36ch;
	}
</style>
