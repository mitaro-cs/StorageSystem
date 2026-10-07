<script lang="ts">
	import { ImagePlus, Check } from '@lucide/svelte';
	import { get, put } from '$lib/api';
	import { putFile } from '$lib/upload';
	import { PRESETS, parseBackground, rememberBackground } from '$lib/appearance';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';

	// Фон страниц входа, регистрации и приглашения – то, что одногруппники видят первым.
	let value = $state<string | null>(null);
	let busy = $state(false);
	let input: HTMLInputElement | undefined = $state();
	// Предпросмотр – в окне, а не в новой вкладке: в приложении хоста новые вкладки не открываются.
	let preview = $state(false);

	$effect(() => {
		get<{ loginBackground: string }>('/api/appearance').then((r) => (value = r.loginBackground));
	});

	const current = $derived(parseBackground(value));

	async function choose(v: string) {
		busy = true;
		try {
			const r = await put<{ loginBackground: string }>('/api/admin/appearance', {
				loginBackground: v
			});
			value = r.loginBackground;
			rememberBackground(value);
			toast('Фон сохранён', 'ok');
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}

	async function upload(files: FileList | null) {
		const f = files?.[0];
		if (!f) return;
		busy = true;
		try {
			const r = await putFile<{ loginBackground: string }>(
				'/api/admin/appearance/background',
				f,
				f.type || 'application/octet-stream'
			);
			value = r.loginBackground;
			rememberBackground(value);
			toast('Картинка поставлена фоном', 'ok');
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
			if (input) input.value = '';
		}
	}
</script>

<section class="card block">
	<p class="muted lead">
		Фон видят все, кто открывает ссылку на сайт: на странице входа, регистрации и приглашения.
	</p>

	{#if value !== null}
		<div class="tiles" role="radiogroup" aria-label="Фон">
			{#each PRESETS as p (p.key)}
				{@const on = current.preset === p.key}
				<button
					class="tile"
					class:on
					role="radio"
					aria-checked={on}
					disabled={busy}
					onclick={() => choose(`preset:${p.key}`)}
				>
					<span class="preview login-bg-{p.key}"><span class="mini-card"></span></span>
					<span class="name"
						>{#if on}<Check size={14} />{/if}{p.label}</span
					>
				</button>
			{/each}
			<button
				class="tile"
				class:on={value === 'none'}
				role="radio"
				aria-checked={value === 'none'}
				disabled={busy}
				onclick={() => choose('none')}
			>
				<span class="preview plain"><span class="mini-card"></span></span>
				<span class="name"
					>{#if value === 'none'}<Check size={14} />{/if}Без рисунка</span
				>
			</button>
			<button
				class="tile"
				class:on={!!current.image}
				role="radio"
				aria-checked={!!current.image}
				disabled={busy}
				onclick={() => input?.click()}
			>
				<span
					class="preview photo"
					style:background-image={current.image ? `url(${current.image})` : null}
				>
					{#if !current.image}<ImagePlus size={22} />{:else}<span class="mini-card"></span>{/if}
				</span>
				<span class="name"
					>{#if current.image}<Check size={14} />{/if}Своя картинка</span
				>
			</button>
		</div>
		<input
			bind:this={input}
			type="file"
			accept="image/png,image/jpeg,image/webp"
			hidden
			onchange={(e) => upload(e.currentTarget.files)}
		/>
		<p class="faint small">
			Своя картинка: JPEG, PNG или WebP до 10 МБ. Лучше горизонтальная – фото корпуса, аудитории или
			группы. Картинку уменьшим до 1920 точек и уберём из неё данные о месте съёмки.
		</p>
		<div class="row wrap">
			<Button onclick={() => input?.click()} loading={busy}
				><ImagePlus size={16} /> Загрузить картинку</Button
			>
			<Button variant="ghost" onclick={() => (preview = true)}>Посмотреть страницу входа</Button>
		</div>
	{/if}
</section>

{#if preview}
	{#await import('$lib/ui/Modal.svelte') then M}
		<M.default
			bind:open={preview}
			title="Страница входа"
			subtitle="Так её видят одногруппники"
			wide
		>
			<div class="frames">
				{#key value}
					<div class="box wide">
						<iframe src="/login?preview=1" title="Страница входа на компьютере"></iframe>
					</div>
					<div class="box phone">
						<iframe src="/login?preview=1" title="Страница входа на телефоне"></iframe>
					</div>
				{/key}
			</div>
		</M.default>
	{/await}
{/if}

<!-- Оформление для себя – у каждого своё, в личных настройках. -->
<p class="faint small">
	Тема, дизайн и цвет интерфейса у каждого свои – <a href="/profile?tab=appearance"
		>в ваших настройках</a
	>.
</p>

<style>
	.frames {
		display: flex;
		gap: 12px;
		align-items: flex-start;
	}
	/* Рамки в половину настоящего размера: страница внутри – как на экране компьютера и телефона. */
	.box {
		position: relative;
		overflow: hidden;
		border: 1px solid var(--border);
		border-radius: 14px;
		background: var(--bg);
	}
	.box iframe {
		position: absolute;
		top: 0;
		left: 0;
		width: 200%;
		max-width: none;
		height: 200%;
		border: 0;
		transform: scale(0.5);
		transform-origin: 0 0;
		pointer-events: none;
	}
	.wide {
		flex: 1;
		min-width: 0;
		aspect-ratio: 16 / 10;
	}
	.phone {
		flex: none;
		width: 195px;
		height: 422px;
	}
	@media (max-width: 640px) {
		.frames {
			justify-content: center;
		}
		.wide {
			display: none;
		}
	}
	.block {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		padding: var(--s5);
	}
	.lead {
		margin: 0;
	}
	.tiles {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
		gap: var(--s3);
	}
	.tile {
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding: 6px 6px 10px;
		border: 0;
		border-radius: 16px;
		background: var(--surface-2);
		color: var(--text-2);
		font: inherit;
		font-size: 13.5px;
		font-weight: 600;
		transition:
			transform 140ms var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.tile:hover {
		transform: translateY(-2px);
	}
	.tile.on {
		color: var(--text);
		box-shadow: inset 0 0 0 2px var(--text);
	}
	.preview {
		position: relative;
		display: grid;
		place-items: center;
		height: 92px;
		border-radius: 12px;
		overflow: hidden;
		animation: none;
		color: var(--text-3);
	}
	.plain {
		background: var(--bg);
	}
	.photo {
		background-color: var(--bg);
		background-size: cover;
		background-position: center;
	}
	.mini-card {
		width: 44%;
		height: 56%;
		border-radius: 6px;
		background: var(--surface);
		box-shadow: 0 4px 12px rgb(0 0 0 / 0.14);
	}
	.name {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 4px;
	}
</style>
