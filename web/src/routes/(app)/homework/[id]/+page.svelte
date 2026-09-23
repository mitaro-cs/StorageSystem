<script lang="ts">
	import { offline } from '$lib/offline/engine';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { Paperclip, MessageCircle, Check, RotateCcw, Clock } from '@lucide/svelte';
	import { del, get, put } from '$lib/api';
	import { fmtAgo, fmtDue, fmtSize, plural, relativeDay } from '$lib/format';
	import { can, isMulti } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Homework } from '$lib/types';
	import { toggleDone } from '$lib/content/homework';
	import Author from '$lib/content/Author.svelte';
	import Comments from '$lib/content/Comments.svelte';
	import HomeworkComposer from '$lib/content/HomeworkComposer.svelte';
	import SubjectTag from '$lib/ui/SubjectTag.svelte';
	import BackBar from '$lib/ui/BackBar.svelte';
	import SubjectArt from '$lib/ui/SubjectArt.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Prose from '$lib/ui/Prose.svelte';
	import FileIcon from '$lib/content/FileIcon.svelte';

	let item = $state<Homework | null>(null);
	let missing = $state(false);
	let composer = $state(false);
	const now = Date.now();

	async function load() {
		try {
			item = await get<Homework>(`/api/homework/${page.params.id}`);
		} catch {
			missing = true;
		}
	}

	$effect(() => {
		void page.params.id;
		void offline.version;
		load();
	});

	const actions = $derived.by((): MenuItem[] => {
		if (!item) return [];
		const h = item;
		const out: MenuItem[] = [];
		if (h.can.edit) out.push({ label: 'Изменить', onclick: () => (composer = true) });
		if (h.can.hide)
			out.push({
				label: h.hidden ? 'Вернуть' : 'Скрыть',
				onclick: async () => {
					try {
						await put(`/api/homework/${h.id}/hidden`, { value: !h.hidden });
						load();
					} catch (e) {
						toastError(e);
					}
				}
			});
		if (h.can.delete)
			out.push({
				label: 'Удалить',
				danger: true,
				onclick: async () => {
					if (!confirm('Удалить задание?')) return;
					try {
						await del(`/api/homework/${h.id}`);
						toast('Задание удалено', 'ok');
						goto('/homework', { replaceState: true });
					} catch (e) {
						toastError(e);
					}
				}
			});
		return out;
	});

	const overdue = $derived(item ? !item.done && item.dueAt < now : false);
	const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
</script>

<svelte:head><title>{item?.title ?? 'Задание'} · groupbase</title></svelte:head>

<BackBar href="/homework" label="Задания"><Menu items={actions} /></BackBar>

{#if missing}
	<div class="card">
		<Empty title="Задание не найдено" text="Его удалили или оно адресовано другой группе." />
	</div>
{:else if !item}
	<Skeleton lines={6} />
{:else}
	<article class="hw">
		<!-- Карточка-обложка: инверсная, как главная карточка на макете -->
		<header class="hero">
			<span class="cover">
				<SubjectArt {...item.subject} class="fill" />
				<span class="badge num">{cap(relativeDay(item.dueAt, now))}</span>
			</span>
			<div class="hero-info">
				<h1>{item.title}</h1>
				<span class="sub">{item.subject.name}</span>
				<span class="facts">
					<span><Clock size={15} /> <span class="num">{fmtDue(item.dueAt, now)}</span></span>
					{#if item.attachments.length}<span
							><Paperclip size={15} />
							<span class="num">{item.attachments.length}</span>
							{plural(item.attachments.length, ['файл', 'файла', 'файлов'])}</span
						>{/if}
					{#if item.comments}<span
							><MessageCircle size={15} /> <span class="num">{item.comments}</span></span
						>{/if}
				</span>
			</div>
		</header>

		<dl class="kv">
			<div>
				<dt>Статус</dt>
				<dd class:ok={item.done} class:bad={overdue}>
					{item.done ? 'Выполнено' : overdue ? 'Срок прошёл' : 'Не выполнено'}
				</dd>
			</div>
			<div>
				<dt>Срок</dt>
				<dd class="num">{fmtDue(item.dueAt, now)}</dd>
			</div>
			<div>
				<dt>Предмет</dt>
				<dd><SubjectTag {...item.subject} /></dd>
			</div>
			{#if isMulti()}
				<div>
					<dt>Группы</dt>
					<dd>{item.groups.map((g) => g.name).join(', ')}</dd>
				</div>
			{/if}
			<div>
				<dt>Автор</dt>
				<dd class="author"><Author person={item.author} size={22} /></dd>
			</div>
			<div>
				<dt>Опубликовано</dt>
				<dd class="num">{fmtAgo(item.createdAt)}</dd>
			</div>
		</dl>

		{#if item.hidden}<p class="tip">
				<span><strong>Скрыто.</strong> Задание видят только те, кто может его вернуть.</span>
			</p>{/if}
		{#if item.done}<p class="tip">
				<span>Отметка «выполнено» видна <strong>только вам</strong>.</span>
			</p>{/if}

		{#if item.bodyHtml}<div class="body"><Prose html={item.bodyHtml} /></div>{/if}

		{#if item.attachments.length}
			<section>
				<div class="section-head">
					<h2>Файлы</h2>
					<span class="aside num">{item.attachments.length}</span>
				</div>
				<div class="list">
					{#each item.attachments as f (f.id)}
						<a class="list-row" href="/api/files/{f.id}" target="_blank" rel="noopener">
							<FileIcon mime={f.mime} size={20} />
							<span class="fname">{f.name}</span>
							<span class="faint small num">{fmtSize(f.size)}</span>
						</a>
					{/each}
				</div>
			</section>
		{/if}
	</article>
	<Comments
		base="/api/homework/{item.id}"
		canComment={item.groups.some((g) => can('comment', g.id))}
	/>
	<div class="cta-space" aria-hidden="true"></div>
	<button
		class="glass-cta"
		aria-pressed={item.done}
		onclick={() => item && toggleDone(item, !item.done)}
	>
		{#if item.done}<RotateCcw size={18} /> Вернуть в работу{:else}<Check size={19} /> Отметить выполненным{/if}
	</button>
	<HomeworkComposer bind:open={composer} edit={item} onsaved={(h) => (item = h)} />
{/if}

<style>
	.hw {
		display: flex;
		flex-direction: column;
		gap: var(--s5);
		margin-bottom: var(--s6);
	}
	.hero {
		display: grid;
		grid-template-columns: minmax(120px, 40%) 1fr;
		gap: var(--s4);
		padding: 12px;
		border-radius: var(--r-xl);
		background: var(--inverse);
		color: var(--inverse-text);
		box-shadow: var(--shadow-2);
	}
	.cover {
		position: relative;
		min-height: 160px;
	}
	.cover :global(.fill) {
		position: absolute;
		inset: 0;
		border-radius: 18px;
	}
	.badge {
		position: absolute;
		left: 10px;
		bottom: 10px;
		padding: 6px 12px;
		border-radius: 12px;
		background: rgb(0 0 0 / 0.78);
		color: #fff;
		font: 600 14px var(--mono);
	}
	.hero-info {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
		padding: 6px 4px 2px 0;
	}
	h1 {
		font-size: clamp(20px, 4.4vw, 26px);
		line-height: 1.2;
		overflow-wrap: anywhere;
	}
	.sub {
		color: var(--inverse-muted);
	}
	.facts {
		display: inline-flex;
		flex-wrap: wrap;
		align-self: flex-start;
		align-items: center;
		margin-top: auto;
		padding: 8px 4px;
		border-radius: 14px;
		background: var(--inverse-2);
		color: var(--inverse-muted);
		font-size: 13.5px;
	}
	.facts > span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 0 10px;
	}
	.facts > span + span {
		border-left: 1px solid color-mix(in srgb, var(--inverse-muted) 40%, transparent);
	}
	.kv dd.ok {
		color: var(--ok);
	}
	.kv dd.bad {
		color: var(--danger);
	}
	.author {
		display: flex;
		justify-content: flex-end;
		min-width: 0;
	}
	.body {
		font-size: 16px;
	}
	.fname {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.cta-space {
		height: 88px;
	}
	/* Узко — пункты переносятся: разделитель-черта тогда только мешает */
	@media (max-width: 480px) {
		.facts > span + span {
			border-left: 0;
		}
	}
</style>
