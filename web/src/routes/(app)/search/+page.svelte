<script lang="ts">
	import { replaceState } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount, untrack } from 'svelte';
	import { BookOpen, CalendarCheck, FileText, Newspaper, Search, X } from '@lucide/svelte';
	import { get } from '$lib/api';
	import { fmtAgo, fmtDue } from '$lib/format';
	import { fly, stagger } from '$lib/motion';
	import { session } from '$lib/session.svelte';
	import { sortedSubjects } from '$lib/data.svelte';
	import Select from '$lib/ui/Select.svelte';
	import type { SearchKind, SearchResult, SearchSegment } from '$lib/types';
	import Empty from '$lib/ui/Empty.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';

	const KINDS: { value: SearchKind | 'all'; label: string }[] = [
		{ value: 'all', label: 'Всё' },
		{ value: 'homework', label: 'Задания' },
		{ value: 'news', label: 'Новости' },
		{ value: 'material', label: 'Материалы' },
		{ value: 'subject', label: 'Предметы' },
		{ value: 'file', label: 'В файлах' }
	];
	const ICONS = {
		homework: CalendarCheck,
		news: Newspaper,
		material: FileText,
		subject: BookOpen,
		file: FileText
	};
	const HISTORY = 'gb-searches';

	let q = $state(page.url.searchParams.get('q') ?? '');
	let kind = $state<SearchKind | 'all'>(
		(page.url.searchParams.get('kind') as SearchKind | null) ?? 'all'
	);
	// Предмет и порядок (1.0.2) – тоже в адресе: ссылкой можно поделиться.
	let subject = $state(Number(page.url.searchParams.get('subject')) || 0);
	let sort = $state<'rank' | 'new'>(page.url.searchParams.get('sort') === 'new' ? 'new' : 'rank');
	const subjectOptions = $derived([
		{ value: 0, label: 'Все предметы' },
		...sortedSubjects(session.groupId).map((s) => ({ value: s.id, label: s.name }))
	]);
	let result = $state<SearchResult | null>(null);
	let loading = $state(false);
	let history = $state<string[]>([]);
	let inputEl: HTMLInputElement | undefined = $state();
	let seq = 0;

	onMount(() => {
		try {
			history = JSON.parse(localStorage.getItem(HISTORY) ?? '[]');
		} catch {
			history = [];
		}
		inputEl?.focus();
	});

	function remember(query: string) {
		history = [query, ...history.filter((h) => h !== query)].slice(0, 6);
		try {
			localStorage.setItem(HISTORY, JSON.stringify(history));
		} catch {
			/* не запоминаем */
		}
	}

	// Ищем с задержкой, пока человек печатает; устаревшие ответы отбрасываем. Запрос попадает в
	// адресную строку (можно поделиться ссылкой) – без перехода и перезагрузки данных.
	$effect(() => {
		const query = q.trim();
		const k = kind;
		const sub = subject;
		const order = sort;
		const group = session.groupId;
		const my = ++seq;
		if (query.length < 2) result = null;
		loading = query.length >= 2;
		const t = setTimeout(async () => {
			const qs = [
				query ? `q=${encodeURIComponent(query)}` : '',
				k !== 'all' ? `kind=${k}` : '',
				sub ? `subject=${sub}` : '',
				order === 'new' ? 'sort=new' : ''
			]
				.filter(Boolean)
				.join('&');
			try {
				untrack(() => replaceState(`/search${qs ? `?${qs}` : ''}`, {}));
			} catch {
				/* роутер ещё не готов – адрес обновится при следующем вводе */
			}
			if (query.length < 2) return;
			const params = [qs, group ? `group=${group}` : ''].filter(Boolean).join('&');
			try {
				const r = await get<SearchResult>(`/api/search?${params}`);
				if (my === seq) {
					result = r;
					if (r.items.length) remember(query);
				}
			} finally {
				if (my === seq) loading = false;
			}
		}, 250);
		return () => clearTimeout(t);
	});

	const total = $derived(
		result ? Object.values(result.counts).reduce((a, b) => a + (b ?? 0), 0) : 0
	);

	function when(kindOf: SearchKind, date: number | null): string {
		if (date === null) return '';
		return kindOf === 'homework' ? `срок ${fmtDue(date)}` : fmtAgo(date);
	}

	// Стрелки: из поля – к первому результату, дальше – по списку (1.0.2).
	function onkeydown(e: KeyboardEvent) {
		if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
		const links = [...document.querySelectorAll<HTMLAnchorElement>('.results a.hit')];
		if (!links.length) return;
		const at = links.indexOf(document.activeElement as HTMLAnchorElement);
		const next = e.key === 'ArrowDown' ? at + 1 : at - 1;
		e.preventDefault();
		if (next < 0) inputEl?.focus();
		else links[Math.min(next, links.length - 1)].focus();
	}

	function text(segs: SearchSegment[]): string {
		return segs.map((s) => s.text).join('');
	}
</script>

<svelte:head><title>{q ? `${q} · ` : ''}Поиск · Campus</title></svelte:head>
<svelte:window {onkeydown} />

<h1 class="title">Поиск</h1>

<label class="search">
	<Search size={20} />
	<input
		bind:this={inputEl}
		bind:value={q}
		type="search"
		enterkeyhint="search"
		placeholder="Задание, лекция, преподаватель…"
		aria-label="Что найти"
		autocomplete="off"
	/>
	{#if q}
		<button class="circle clear" onclick={() => ((q = ''), inputEl?.focus())} aria-label="Очистить"
			><X size={18} /></button
		>
	{/if}
</label>

<div class="chips" role="group" aria-label="Где искать">
	{#each KINDS as k (k.value)}
		<button class="chip-btn" class:on={kind === k.value} onclick={() => (kind = k.value)}>
			{k.label}
			{#if result && kind === 'all' && k.value !== 'all' && result.counts[k.value]}<span
					class="num count">{result.counts[k.value]}</span
				>{/if}
		</button>
	{/each}
</div>

<div class="tools">
	<span class="pick"><Select bind:value={subject} options={subjectOptions} label="Предмет" /></span>
	<div class="seg" role="radiogroup" aria-label="Порядок">
		<button
			role="radio"
			aria-checked={sort === 'rank'}
			class:on={sort === 'rank'}
			onclick={() => (sort = 'rank')}>Подходящие</button
		>
		<button
			role="radio"
			aria-checked={sort === 'new'}
			class:on={sort === 'new'}
			onclick={() => (sort = 'new')}>Новые</button
		>
	</div>
</div>
<p class="faint small syntax">
	<code>"точная фраза"</code> – слова подряд · <code>-слово</code> – без него · ищем и внутри PDF, Word
	и конспектов
</p>

{#if q.trim().length < 2}
	{#if history.length}
		<div class="section-head"><h2>Вы искали</h2></div>
		<div class="history">
			{#each history as h (h)}
				<button class="chip-btn" onclick={() => (q = h)}><Search size={14} /> {h}</button>
			{/each}
		</div>
	{:else}
		<div class="card">
			<Empty
				title="Что ищем?"
				text="Название задания, слово из новости, имя файла лекции или фамилию преподавателя. Окончания не важны: «задача» найдёт и «задачи»."
			/>
		</div>
	{/if}
{:else if !result}
	<Skeleton lines={4} />
{:else if result.items.length === 0}
	<div class="card">
		<Empty
			title="Ничего не нашли"
			text="Проверьте опечатки или попробуйте другое слово. Скрытое и чужие группы в поиск не попадают."
		/>
	</div>
{:else}
	<p class="faint small total" aria-live="polite">
		{loading ? 'Ищем…' : `Найдено: ${kind === 'all' ? total : result.items.length}`}
	</p>
	<ul class="results">
		{#each result.items as r, i (`${r.kind}-${r.id}`)}
			{@const Icon = ICONS[r.kind]}
			<li in:fly={{ y: 6, delay: stagger(i, 20) }}>
				<a class="hit" href={r.url} aria-label={text(r.title)}>
					<span class="icon" style:--c={r.subject?.color ?? 'var(--text-3)'}
						><Icon size={19} /></span
					>
					<span class="body">
						<strong class="t"
							>{#each r.title as s, j (j)}{#if s.hit}<mark>{s.text}</mark
									>{:else}{s.text}{/if}{/each}</strong
						>
						{#if r.file}<span class="file faint small"><FileText size={13} /> {r.file}</span>{/if}
						{#if r.snippet.length && r.kind !== 'subject'}
							<span class="snip"
								>{#each r.snippet as s, j (j)}{#if s.hit}<mark>{s.text}</mark
										>{:else}{s.text}{/if}{/each}</span
							>
						{/if}
						<span class="meta faint small">
							{#if r.subject && r.kind !== 'subject'}<span
									class="dot"
									style:background={r.subject.color}
								></span>{r.subject.name}{/if}
							{#if r.kind === 'subject' && r.snippet.length}{text(r.snippet)}{/if}
							{#if when(r.kind, r.date)}<span class="num"
									>{r.subject && r.kind !== 'subject' ? '· ' : ''}{when(r.kind, r.date)}</span
								>{/if}
							{#if r.hidden}<span class="chip">скрыто</span>{/if}
						</span>
					</span>
				</a>
			</li>
		{/each}
	</ul>
{/if}

<style>
	.tools {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 10px;
		margin: calc(-1 * var(--s3)) 0 var(--s2);
	}
	.pick {
		min-width: 200px;
		flex: 1;
		max-width: 320px;
	}
	.seg {
		display: inline-flex;
		padding: 3px;
		border-radius: var(--r-full);
		background: var(--surface-2);
	}
	.seg button {
		height: 32px;
		padding: 0 14px;
		border: 0;
		border-radius: var(--r-full);
		background: none;
		color: var(--text-2);
		font: inherit;
		font-size: 13px;
		font-weight: 600;
	}
	.seg button.on {
		background: var(--surface);
		color: var(--text);
		box-shadow: 0 1px 3px rgb(0 0 0 / 0.08);
	}
	.syntax {
		margin: 0 0 var(--s4);
	}
	.syntax code {
		padding: 1px 6px;
		border-radius: 6px;
		background: var(--surface-2);
		font-size: 12px;
	}
	.file {
		display: inline-flex;
		align-items: center;
		gap: 5px;
	}
	.title {
		margin: var(--s2) 0 var(--s4);
	}
	.search {
		display: flex;
		align-items: center;
		gap: 12px;
		height: 60px;
		padding: 0 8px 0 20px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-3);
		transition:
			border-color var(--dur) var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.search:focus-within {
		border-color: var(--text);
		box-shadow: 0 0 0 3px var(--accent-soft);
	}
	.search input {
		flex: 1;
		min-width: 0;
		height: 100%;
		border: 0;
		background: transparent;
		color: var(--text);
		font-size: 17px;
		outline: none;
	}
	.search input::-webkit-search-cancel-button {
		display: none;
	}
	.clear {
		width: 44px;
		height: 44px;
	}
	.chips,
	.history {
		display: flex;
		gap: 8px;
		margin: var(--s4) calc(-1 * var(--s4)) var(--s5);
		padding: 2px var(--s4);
		overflow-x: auto;
		scrollbar-width: none;
	}
	.history {
		flex-wrap: wrap;
		margin-top: 0;
	}
	.chip-btn {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		height: 38px;
		padding: 0 16px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-2);
		font-size: 14.5px;
		font-weight: 550;
	}
	.chip-btn:hover {
		color: var(--text);
	}
	.chip-btn.on {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	.count {
		font-size: 12px;
		opacity: 0.7;
	}
	.total {
		margin-bottom: var(--s2);
	}
	.results {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.hit {
		display: flex;
		gap: 14px;
		padding: 14px var(--s4);
		border-radius: var(--r-l);
		background: var(--surface);
		box-shadow: var(--shadow-1);
		color: var(--text);
		transition: transform var(--dur) var(--ease);
	}
	.hit:hover {
		text-decoration: none;
		transform: translateY(-1px);
	}
	.icon {
		flex: none;
		display: grid;
		place-items: center;
		width: 44px;
		height: 44px;
		border-radius: 50%;
		background: color-mix(in srgb, var(--c) 16%, transparent);
		color: var(--c);
	}
	.body {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 3px;
	}
	.t {
		font-size: 16px;
		font-weight: 600;
	}
	.snip {
		color: var(--text-2);
		font-size: 14px;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.meta {
		display: flex;
		align-items: center;
		gap: 6px;
		flex-wrap: wrap;
	}
	.dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
	}
	mark {
		background: var(--amber-soft);
		color: inherit;
		border-radius: 4px;
		padding: 0 2px;
	}
	@media (min-width: 900px) {
		.chips,
		.history {
			margin-left: 0;
			margin-right: 0;
			padding-left: 0;
			padding-right: 0;
		}
	}
</style>
