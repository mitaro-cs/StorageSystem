<script lang="ts">
	import { ChevronLeft, ChevronRight, Download, Minus, Plus, X } from '@lucide/svelte';
	import { fmtSize } from '$lib/format';
	import { fileKind, TEXT_LIMIT } from '$lib/fileKinds';
	import FileIcon from '$lib/content/FileIcon.svelte';
	import { closeViewer, viewer } from './viewer.svelte';

	// Просмотр файлов поверх приложения: картинки, PDF, видео, аудио и текст открываются сразу,
	// без скачивания и без выхода из приложения. Остальное — «Скачать».
	let dialog: HTMLDialogElement | undefined = $state();
	let zoom = $state(1);
	let text = $state<string | null>(null);
	let textError = $state('');

	const file = $derived(viewer.files[viewer.index]);
	const kind = $derived(file ? fileKind(file.mime, file.name) : 'other');
	const src = $derived(file ? `/api/files/${file.id}` : '');
	const pending = $derived(!!file && file.id <= 0);
	const many = $derived(viewer.files.length > 1);

	$effect(() => {
		if (!dialog) return;
		if (viewer.open && !dialog.open) dialog.showModal();
		if (!viewer.open && dialog.open) dialog.close();
	});

	// Новый файл — масштаб сначала, текст заново.
	$effect(() => {
		void file?.id;
		zoom = 1;
		text = null;
		textError = '';
		if (!file || pending || kind !== 'text') return;
		if (file.size > TEXT_LIMIT) {
			textError = 'Файл большой — скачайте его';
			return;
		}
		const id = file.id;
		fetch(`/api/files/${id}`, { credentials: 'same-origin' })
			.then((r) => (r.ok ? r.text() : Promise.reject(new Error(`Ошибка ${r.status}`))))
			.then((t) => {
				if (file?.id === id) text = t;
			})
			.catch((e) => (textError = e instanceof Error ? e.message : 'Не удалось открыть'));
	});

	function go(step: number) {
		if (!many) return;
		const n = viewer.files.length;
		viewer.index = (viewer.index + step + n) % n;
	}

	function onkey(e: KeyboardEvent) {
		if (!viewer.open) return;
		if (e.key === 'ArrowRight') go(1);
		else if (e.key === 'ArrowLeft') go(-1);
		else if ((e.key === '+' || e.key === '=') && zoomable) setZoom(zoom + 0.25);
		else if (e.key === '-' && zoomable) setZoom(zoom - 0.25);
	}

	const zoomable = $derived(kind === 'image' || kind === 'pdf');
	function setZoom(z: number) {
		zoom = Math.round(Math.min(4, Math.max(0.5, z)) * 100) / 100;
	}

	// Свайп влево-вправо — соседний файл (когда картинка не увеличена).
	let startX = 0;
	let startY = 0;
	function down(e: PointerEvent) {
		startX = e.clientX;
		startY = e.clientY;
	}
	function up(e: PointerEvent) {
		if (zoom !== 1 || e.pointerType !== 'touch') return;
		const dx = e.clientX - startX;
		if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(e.clientY - startY) * 1.5) go(dx < 0 ? 1 : -1);
	}

	// Двойное касание картинки — приблизить или вернуть (масштаб страницы сам не меняется).
	let lastTap = 0;
	function tapImage(e: PointerEvent) {
		if (e.pointerType !== 'touch') return;
		const now = Date.now();
		if (now - lastTap < 300) setZoom(zoom === 1 ? 2 : 1);
		lastTap = now;
	}
</script>

<svelte:window onkeydown={onkey} />

<dialog
	bind:this={dialog}
	class="viewer"
	aria-label={file ? `Просмотр: ${file.name}` : 'Просмотр файла'}
	onclose={() => viewer.open && closeViewer()}
>
	{#if viewer.open && file}
		<header class="bar">
			<button class="icon-btn" onclick={closeViewer} aria-label="Закрыть"><X size={20} /></button>
			<div class="name">
				<strong>{file.name}</strong>
				<span class="small num"
					>{fmtSize(file.size)}{many
						? ` · ${viewer.index + 1} из ${viewer.files.length}`
						: ''}{viewer.title ? ` · ${viewer.title}` : ''}</span
				>
			</div>
			{#if zoomable && !pending}
				<div class="zoom" role="group" aria-label="Масштаб">
					<button class="icon-btn" onclick={() => setZoom(zoom - 0.25)} aria-label="Уменьшить"
						><Minus size={18} /></button
					>
					<button class="pct num" onclick={() => setZoom(1)} title="Вписать в экран"
						>{Math.round(zoom * 100)}%</button
					>
					<button class="icon-btn" onclick={() => setZoom(zoom + 0.25)} aria-label="Увеличить"
						><Plus size={18} /></button
					>
				</div>
			{/if}
			{#if !pending}
				<a class="icon-btn" href="{src}?download=true" download={file.name} aria-label="Скачать"
					><Download size={19} /></a
				>
			{/if}
		</header>

		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div
			class="stage"
			class:scroll={kind === 'pdf' || kind === 'text' || zoom !== 1}
			onpointerdown={down}
			onpointerup={up}
		>
			{#key file.id}
				<div class="content {kind}">
					{#if pending}
						<div class="none">
							<FileIcon mime={file.mime} size={34} />
							<p>Файл ещё на этом устройстве — откроется, когда уйдёт на сервер.</p>
						</div>
					{:else if kind === 'image'}
						<img
							{src}
							alt={file.name}
							style:width={zoom === 1 ? null : `${zoom * 100}%`}
							class:zoomed={zoom !== 1}
							onpointerup={tapImage}
							ondblclick={() => setZoom(zoom === 1 ? 2 : 1)}
						/>
					{:else if kind === 'pdf'}
						{#await import('./PdfView.svelte') then m}<m.default {src} {zoom} />{/await}
					{:else if kind === 'video'}
						<!-- svelte-ignore a11y_media_has_caption -->
						<video {src} controls playsinline preload="metadata"></video>
					{:else if kind === 'audio'}
						<div class="none">
							<FileIcon mime={file.mime} size={34} />
							<audio {src} controls preload="metadata"></audio>
						</div>
					{:else if kind === 'text'}
						{#if textError}<p class="none">{textError}</p>
						{:else if text === null}<p class="none faint">Открываем…</p>
						{:else}<pre>{text}</pre>{/if}
					{:else}
						<div class="none">
							<FileIcon mime={file.mime} size={40} />
							<p>
								<strong>{file.name}</strong><br /><span class="dim"
									>Этот файл не открыть прямо здесь — скачайте его и откройте в подходящей
									программе.</span
								>
							</p>
							<a class="btn-dl" href="{src}?download=true" download={file.name}
								><Download size={17} /> Скачать · {fmtSize(file.size)}</a
							>
						</div>
					{/if}
				</div>
			{/key}
		</div>

		{#if many}
			<button class="nav prev" onclick={() => go(-1)} aria-label="Предыдущий файл"
				><ChevronLeft size={24} /></button
			>
			<button class="nav next" onclick={() => go(1)} aria-label="Следующий файл"
				><ChevronRight size={24} /></button
			>
			<div class="dots" aria-hidden="true">
				{#each viewer.files as f, i (f.id)}<span class:on={i === viewer.index}></span>{/each}
			</div>
		{/if}
	{/if}
</dialog>

<style>
	.viewer {
		width: 100vw;
		height: 100dvh;
		max-width: none;
		max-height: none;
		margin: 0;
		padding: 0;
		border: 0;
		background: #0e0f12;
		color: #f3f4f6;
		overflow: hidden;
	}
	.viewer[open] {
		display: flex;
		flex-direction: column;
		animation: viewer-in 220ms var(--ease);
	}
	.viewer::backdrop {
		background: transparent;
	}
	@keyframes viewer-in {
		from {
			opacity: 0;
			transform: scale(0.985);
		}
	}
	.bar {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: calc(8px + env(safe-area-inset-top)) 10px 8px;
		background: linear-gradient(rgb(0 0 0 / 0.45), transparent);
	}
	.name {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		line-height: 1.3;
	}
	.name strong {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-weight: 600;
	}
	.name span {
		color: rgb(255 255 255 / 0.6);
	}
	.icon-btn {
		display: grid;
		place-items: center;
		flex: none;
		width: 40px;
		height: 40px;
		border: 0;
		border-radius: 12px;
		background: transparent;
		color: inherit;
	}
	.icon-btn:hover {
		background: rgb(255 255 255 / 0.1);
		text-decoration: none;
	}
	.zoom {
		display: flex;
		align-items: center;
	}
	.pct {
		min-width: 52px;
		height: 32px;
		border: 0;
		border-radius: 8px;
		background: rgb(255 255 255 / 0.08);
		color: inherit;
		font: inherit;
		font-size: 13px;
	}
	@media (max-width: 520px) {
		.pct {
			display: none;
		}
	}
	.stage {
		position: relative;
		flex: 1;
		min-height: 0;
		display: flex;
		overflow: hidden;
		touch-action: pan-y;
	}
	.stage.scroll {
		overflow: auto;
		touch-action: pan-x pan-y;
	}
	.content {
		margin: auto;
		padding: 12px 12px calc(28px + env(safe-area-inset-bottom));
		max-width: 100%;
		animation: content-in 200ms var(--ease);
	}
	@keyframes content-in {
		from {
			opacity: 0;
			transform: translateY(6px);
		}
	}
	.content.pdf {
		width: min(100%, 980px);
		margin: 0 auto;
	}
	.content.image {
		display: grid;
		place-items: center;
		width: 100%;
		height: 100%;
		padding: 0 12px 24px;
	}
	img {
		max-width: 100%;
		max-height: 100%;
		object-fit: contain;
		border-radius: 6px;
		user-select: none;
		-webkit-user-select: none;
		cursor: zoom-in;
	}
	img.zoomed {
		max-width: none;
		max-height: none;
		cursor: zoom-out;
	}
	video {
		display: block;
		max-width: 100%;
		max-height: calc(100dvh - 120px);
		border-radius: 8px;
		background: black;
	}
	.content.text {
		width: min(100%, 980px);
		margin: 0 auto;
	}
	pre {
		margin: 0;
		padding: 16px;
		border-radius: 10px;
		background: rgb(255 255 255 / 0.06);
		color: #e5e7eb;
		font:
			13px/1.55 ui-monospace,
			'SF Mono',
			Menlo,
			Consolas,
			monospace;
		white-space: pre-wrap;
		overflow-wrap: anywhere;
		user-select: text;
	}
	.none {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 16px;
		max-width: 380px;
		margin: 15vh auto 0;
		text-align: center;
		color: rgb(255 255 255 / 0.85);
	}
	.dim {
		color: rgb(255 255 255 / 0.6);
		font-size: 14px;
	}
	.btn-dl {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		height: 44px;
		padding: 0 20px;
		border-radius: var(--r-full);
		background: white;
		color: #111;
		font-weight: 600;
	}
	.btn-dl:hover {
		text-decoration: none;
		transform: translateY(-1px);
	}
	.nav {
		position: absolute;
		top: 50%;
		display: grid;
		place-items: center;
		width: 44px;
		height: 44px;
		border: 0;
		border-radius: 50%;
		background: rgb(255 255 255 / 0.12);
		color: white;
		backdrop-filter: blur(8px);
		transition: background-color var(--dur) var(--ease);
	}
	.nav:hover {
		background: rgb(255 255 255 / 0.22);
	}
	.prev {
		left: 12px;
	}
	.next {
		right: 12px;
	}
	@media (hover: none) {
		.nav {
			display: none;
		}
	}
	.dots {
		position: absolute;
		left: 50%;
		bottom: calc(10px + env(safe-area-inset-bottom));
		display: flex;
		gap: 6px;
		transform: translateX(-50%);
	}
	.dots span {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: rgb(255 255 255 / 0.3);
		transition:
			width 200ms var(--ease),
			background-color 200ms var(--ease);
	}
	.dots span.on {
		width: 18px;
		border-radius: 3px;
		background: white;
	}
</style>
