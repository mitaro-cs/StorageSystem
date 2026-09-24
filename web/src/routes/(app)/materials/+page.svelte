<script lang="ts">
	import { offline } from '$lib/offline/engine';
	import { page } from '$app/state';
	import { ChevronRight, ClipboardList, Folder, FolderOpen } from '@lucide/svelte';
	import { get, qs } from '$lib/api';
	import { session } from '$lib/session.svelte';
	import { sortedSubjects, subjectById } from '$lib/data.svelte';
	import { fmtDue, fmtSize, plural } from '$lib/format';
	import { fly, stagger } from '$lib/motion';
	import type { Homework, Material } from '$lib/types';
	import { materialActions } from '$lib/content/materialActions';
	import MaterialRow from '$lib/content/MaterialRow.svelte';
	import FileIcon from '$lib/content/FileIcon.svelte';
	import DifficultyBadge from '$lib/content/DifficultyBadge.svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';
	import Crumbs, { type Crumb } from '$lib/ui/Crumbs.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import { openFiles } from '$lib/files/viewer.svelte';
	import { canPreview } from '$lib/fileKinds';

	// Все файлы группы как папки: Файлы › Предмет › Материалы (с их папками) или Задания › задание.
	const params = $derived(page.url.searchParams);
	const subjectId = $derived(params.get('subject') ? Number(params.get('subject')) : null);
	const section = $derived(params.get('section') as 'materials' | 'homework' | null);
	const homeworkId = $derived(params.get('homework') ? Number(params.get('homework')) : null);
	const subject = $derived(subjectId !== null ? subjectById(subjectId) : undefined);

	let recent = $state<Material[] | null>(null);
	let pending = $state<Material[]>([]);
	let homework = $state<Homework[] | null>(null);
	let folderPath = $state<{ id: number; name: string }[]>([]);

	async function loadRoot() {
		const [r, p] = await Promise.all([
			get<Material[]>(`/api/materials/recent${qs({ group: session.groupId, limit: 15 })}`),
			get<Material[]>('/api/materials/pending')
		]);
		recent = r;
		pending = p;
	}

	async function loadHomework(id: number) {
		homework = null;
		homework = await get<Homework[]>(
			`/api/homework${qs({ view: 'all', subject: id, limit: 200 })}`
		);
	}

	$effect(() => {
		void session.groupId;
		void offline.version;
		if (subjectId === null) loadRoot();
		else if (section === 'homework') loadHomework(subjectId);
	});

	$effect(() => {
		if (section !== 'materials') folderPath = [];
	});

	const subjectList = $derived(sortedSubjects(session.groupId));
	const withFiles = $derived((homework ?? []).filter((h) => h.attachments.length));
	const openHw = $derived(homeworkId !== null ? homework?.find((h) => h.id === homeworkId) : null);
	const base = $derived(subjectId !== null ? `/materials?subject=${subjectId}` : '/materials');

	const crumbs = $derived.by((): Crumb[] => {
		const out: Crumb[] = [{ label: 'Файлы', href: '/materials' }];
		if (subjectId === null) return out;
		out.push({ label: subject?.name ?? 'Предмет', href: base });
		if (section === 'materials') {
			out.push({ label: 'Материалы', href: `${base}&section=materials` });
			for (const f of folderPath)
				out.push({ label: f.name, href: `${base}&section=materials&folder=${f.id}` });
		} else if (section === 'homework') {
			out.push({ label: 'Задания', href: `${base}&section=homework` });
			if (openHw) out.push({ label: openHw.title });
		}
		return out;
	});
	const title = $derived(crumbs[crumbs.length - 1].label);
</script>

<svelte:head><title>{title} · groupbase</title></svelte:head>

<div class="page-head"><h1>{subjectId === null ? 'Файлы' : (subject?.name ?? 'Файлы')}</h1></div>
{#if subjectId !== null}<Crumbs items={crumbs} />{/if}

{#if subjectId === null}
	{#if pending.length}
		<section class="block">
			<h2 class="h amber">На проверке · {pending.length}</h2>
			<div class="list">
				{#each pending as m (m.id)}<MaterialRow
						{m}
						showSubject
						actions={materialActions(m, loadRoot)}
					/>{/each}
			</div>
		</section>
	{/if}

	<section class="block">
		<h2 class="h">Предметы</h2>
		{#if subjectList.length === 0}
			<div class="card"><Empty title="Предметов пока нет" /></div>
		{:else}
			<div class="folders">
				{#each subjectList as s, i (s.id)}
					<a class="folder" href="/materials?subject={s.id}" in:fly={{ y: 8, delay: stagger(i) }}>
						<SubjectGlyph name={s.name} color={s.color} icon={s.icon} size={40} />
						<span class="fname">{s.name}</span>
						<ChevronRight size={16} class="chev" />
					</a>
				{/each}
			</div>
		{/if}
	</section>

	<section class="block">
		<h2 class="h">Недавно добавленные</h2>
		{#if recent === null}
			<Skeleton lines={4} />
		{:else if recent.length === 0}
			<div class="card">
				<Empty
					title="Файлов пока нет"
					text="Конспекты, методички и файлы заданий появятся здесь, когда их добавят в предметы."
				/>
			</div>
		{:else}
			<div class="list">
				{#each recent as m, i (m.id)}
					<div in:fly={{ y: 8, delay: stagger(i) }}>
						<MaterialRow {m} showSubject actions={materialActions(m, loadRoot)} />
					</div>
				{/each}
			</div>
		{/if}
	</section>
{:else if !subject}
	<div class="card">
		<Empty title="Предмет не найден" text="Его нет или он не связан с вашей группой." />
	</div>
{:else if section === null}
	<div class="folders two">
		<a class="folder big" href="{base}&section=materials" in:fly={{ y: 8 }}>
			<span class="ficon amber"><FolderOpen size={26} /></span>
			<span class="ftext"
				><span class="fname">Материалы</span><span class="faint small"
					>Конспекты, методички, ссылки — по папкам</span
				></span
			>
			<ChevronRight size={18} class="chev" />
		</a>
		<a class="folder big" href="{base}&section=homework" in:fly={{ y: 8, delay: 40 }}>
			<span class="ficon blue"><ClipboardList size={26} /></span>
			<span class="ftext"
				><span class="fname">Задания</span><span class="faint small"
					>Файлы, приложенные к домашним заданиям</span
				></span
			>
			<ChevronRight size={18} class="chev" />
		</a>
	</div>
{:else if section === 'materials'}
	{#await import('$lib/content/MaterialBrowser.svelte')}<Skeleton lines={4} />{:then m}<m.default
			subjectId={subject.id}
			subjectName={subject.name}
			showPath={false}
			onpath={(p) => (folderPath = p)}
		/>{/await}
{:else if homework === null}
	<Skeleton lines={4} />
{:else if openHw}
	<div class="hw-head">
		<span class="faint small num">Срок: {fmtDue(openHw.dueAt)}</span>
		{#if openHw.difficulty}<DifficultyBadge value={openHw.difficulty} />{/if}
		<a class="small" href="/homework/{openHw.id}">Открыть задание</a>
	</div>
	<div class="list">
		{#each openHw.attachments as f, i (f.id)}
			<button
				class="list-row file"
				onclick={() => openFiles(openHw.attachments, i, openHw.title)}
				in:fly={{ y: 6, delay: stagger(i) }}
			>
				<FileIcon mime={f.mime} size={20} />
				<span class="fname">{f.name}</span>
				<span class="faint small num">{fmtSize(f.size)}</span>
				<span class="open-hint small"
					>{canPreview(f.mime, f.name, f.size) ? 'Открыть' : 'Скачать'}</span
				>
			</button>
		{/each}
	</div>
{:else if withFiles.length === 0}
	<div class="card">
		<Empty
			title="Файлов в заданиях нет"
			text="Когда к заданию по этому предмету приложат файл, он появится здесь."
		/>
	</div>
{:else}
	<div class="folders">
		{#each withFiles as h, i (h.id)}
			<a
				class="folder"
				href="{base}&section=homework&homework={h.id}"
				in:fly={{ y: 8, delay: stagger(i) }}
			>
				<span class="ficon blue"><Folder size={22} /></span>
				<span class="ftext"
					><span class="fname">{h.title}</span><span class="faint small num"
						>{h.attachments.length}
						{plural(h.attachments.length, ['файл', 'файла', 'файлов'])} · {fmtDue(h.dueAt)}</span
					></span
				>
				<ChevronRight size={16} class="chev" />
			</a>
		{/each}
	</div>
{/if}

<style>
	.block {
		margin-bottom: var(--s6);
	}
	.h {
		font-size: 18px;
		margin-bottom: var(--s3);
	}
	.h.amber {
		color: var(--amber);
	}
	.folders {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
		gap: var(--s2);
	}
	.folders.two {
		grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
	}
	.folder {
		display: flex;
		align-items: center;
		gap: 12px;
		min-height: 64px;
		padding: 12px 14px;
		border-radius: var(--r-l);
		background: var(--surface);
		box-shadow: var(--shadow-1);
		color: var(--text);
		transition:
			transform 160ms var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.folder:hover {
		text-decoration: none;
		transform: translateY(-2px);
		box-shadow: var(--shadow-2);
	}
	.folder:active {
		transform: scale(0.985);
	}
	.big {
		min-height: 88px;
	}
	.ficon {
		display: grid;
		place-items: center;
		flex: none;
		width: 44px;
		height: 44px;
		border-radius: 14px;
	}
	.ficon.amber {
		color: var(--amber);
		background: color-mix(in srgb, var(--amber) 14%, transparent);
	}
	.ficon.blue {
		color: var(--accent-2, #3446d4);
		background: color-mix(in srgb, var(--accent-2, #3446d4) 12%, transparent);
	}
	.ftext {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.fname {
		flex: 1;
		min-width: 0;
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.ftext .fname {
		flex: none;
	}
	.folder :global(.chev) {
		flex: none;
		color: var(--text-3);
	}
	.hw-head {
		display: flex;
		align-items: center;
		gap: var(--s3);
		flex-wrap: wrap;
		margin-bottom: var(--s3);
	}
	.file {
		width: 100%;
		border: 0;
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.open-hint {
		flex: none;
		padding: 4px 10px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		color: var(--text-2);
		font-weight: 600;
	}
</style>
