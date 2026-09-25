<script lang="ts">
	import { page } from '$app/state';

	// Страница открылась с ошибкой: понятная причина, код (его удобно прислать старосте) и что делать.
	// Иконка — та же стопка, что на заставке, только рассыпавшаяся.
	const status = $derived(page.status);
	const message = $derived(page.error?.message ?? '');
	const info = $derived.by(() => {
		if (/без интернета|без сети|нужен интернет/i.test(message))
			return {
				code: 'GB-101',
				title: 'Нет подключения к интернету',
				why: message,
				what: 'Эта страница без сети недоступна. Подключитесь и нажмите «Обновить».'
			};
		if (status === 404)
			return {
				code: 'GB-404',
				title: 'Такой страницы нет',
				why: 'Ссылка устарела, запись удалили или в адресе опечатка.',
				what: 'Вернитесь на главную — там всё актуальное.'
			};
		if (status === 401 || status === 403)
			return {
				code: `GB-${status}`,
				title: 'Нет доступа',
				why: 'Эта страница открыта не всем участникам.',
				what: 'Если она нужна — попросите старосту.'
			};
		return {
			code: `GB-${status >= 500 ? 500 : status}`,
			title: 'Не удалось открыть страницу',
			why: message && message !== 'Internal Error' ? message : 'Что-то пошло не так.',
			what: 'Нажмите «Обновить». Если ошибка повторится, пришлите старосте код ошибки.'
		};
	});

	let copied = $state(false);
	async function copy() {
		try {
			await navigator.clipboard.writeText(info.code);
			copied = true;
			setTimeout(() => (copied = false), 1400);
		} catch {
			/* код и так на экране */
		}
	}
</script>

<svelte:head><title>{info.title} · groupbase</title></svelte:head>

<main class="err" role="alert">
	<svg viewBox="-12 -12 56 56" aria-hidden="true">
		<g class="tile">
			<rect width="32" height="32" rx="9" />
			<path class="l l1" d="M9 11.5 16 8l7 3.5-7 3.5-7-3.5Z" />
			<path class="l l2" d="M9 16l7 3.5 7-3.5" />
			<path class="l l3" d="M9 20.5 16 24l7-3.5" />
		</g>
		<g class="badge">
			<circle cx="30" cy="2" r="6" />
			<path d="M30-.8v3.4M30 5.2v.1" />
		</g>
	</svg>
	<h1>{info.title}</h1>
	<p class="why">{info.why}</p>
	<p class="what">{info.what}</p>
	<button class="code num" type="button" onclick={copy} title="Скопировать код">
		{#if copied}Скопировано{:else}Код ошибки <b>{info.code}</b>{/if}
	</button>
	<div class="row">
		<a class="btn primary" href="/">На главную</a>
		<button class="btn" type="button" onclick={() => location.reload()}>Обновить</button>
	</div>
</main>

<style>
	.err {
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 10px;
		padding: var(--s6) var(--s4);
		text-align: center;
	}
	svg {
		width: 128px;
		height: 128px;
		overflow: visible;
		margin-bottom: 4px;
	}
	.tile {
		animation: pop 600ms cubic-bezier(0.2, 0.9, 0.25, 1.3) both;
		transform-box: fill-box;
		transform-origin: center;
	}
	rect {
		fill: var(--text);
	}
	.l {
		fill: none;
		stroke: var(--bg);
		stroke-width: 2.2;
		stroke-linecap: round;
		stroke-linejoin: round;
	}
	.l1 {
		animation: s1 700ms cubic-bezier(0.3, 1.4, 0.5, 1) 200ms forwards;
	}
	.l2 {
		animation: s2 700ms cubic-bezier(0.3, 1.4, 0.5, 1) 260ms forwards;
	}
	.l3 {
		animation: s3 700ms cubic-bezier(0.3, 1.4, 0.5, 1) 320ms forwards;
	}
	.badge {
		transform-box: fill-box;
		transform-origin: center;
		animation: pop 520ms cubic-bezier(0.2, 0.9, 0.25, 1.4) 420ms both;
	}
	.badge circle {
		fill: var(--danger);
		stroke: var(--bg);
		stroke-width: 1.6;
	}
	.badge path {
		fill: none;
		stroke: #fff;
		stroke-width: 1.8;
		stroke-linecap: round;
	}
	h1 {
		margin: 0;
		font-size: clamp(22px, 5vw, 28px);
	}
	.why {
		max-width: 420px;
		margin: 0;
		color: var(--danger);
		font-weight: 600;
	}
	.what {
		max-width: 420px;
		margin: 0;
		color: var(--text-2);
	}
	.code {
		height: 30px;
		padding: 0 12px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-2);
		font: 600 13px/1 var(--mono);
	}
	.code b {
		color: var(--text);
	}
	.row {
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		gap: var(--s2);
		margin-top: var(--s2);
	}
	.btn {
		display: inline-flex;
		align-items: center;
		height: 44px;
		padding: 0 20px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-weight: 600;
		text-decoration: none;
		cursor: pointer;
	}
	.btn.primary {
		border-color: var(--accent);
		background: var(--accent);
		color: var(--accent-text);
	}
	@keyframes pop {
		from {
			opacity: 0;
			transform: scale(0.4) rotate(-16deg);
		}
		to {
			opacity: 1;
			transform: none;
		}
	}
	@keyframes s1 {
		to {
			transform: translate(-2.5px, -2.5px) rotate(-14deg);
		}
	}
	@keyframes s2 {
		to {
			transform: translate(2.5px, 0.5px) rotate(9deg);
		}
	}
	@keyframes s3 {
		to {
			transform: translate(-1px, 2.5px) rotate(-5deg);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.tile,
		.l,
		.badge {
			animation: none;
		}
	}
</style>
