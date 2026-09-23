<script lang="ts">
	import { Plus, Users, Archive, ArrowRight } from '@lucide/svelte';
	import { onMount } from 'svelte';
	import { get, post } from '$lib/api';
	import { can, currentGroup, groups, session } from '$lib/session.svelte';
	import { loadSubjects, subjects } from '$lib/data.svelte';
	import { fly, stagger } from '$lib/motion';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { LinkRequest } from '$lib/types';
	import SubjectEditor from '$lib/content/SubjectEditor.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import SubjectArt from '$lib/ui/SubjectArt.svelte';

	let editor = $state(false);
	let showArchived = $state(false);
	let requests = $state<LinkRequest[]>([]);

	const target = $derived(
		currentGroup() ?? groups().find((g) => g.permissions.includes('manage_subjects'))
	);
	const visible = $derived(
		subjects.list
			.filter((s) => session.groupId === null || s.groups.some((g) => g.id === session.groupId))
			.filter((s) => showArchived || !s.archived)
	);
	const archivedCount = $derived(subjects.list.filter((s) => s.archived).length);

	onMount(async () => {
		requests = await get<LinkRequest[]>('/api/link-requests');
	});

	async function decide(r: LinkRequest, accept: boolean) {
		try {
			await post(`/api/link-requests/${r.id}/${accept ? 'accept' : 'reject'}`);
			requests = requests.filter((x) => x.id !== r.id);
			toast(accept ? 'Предмет стал общим' : 'Запрос отклонён', 'ok');
			loadSubjects();
		} catch (e) {
			toastError(e);
		}
	}
</script>

<svelte:head><title>Предметы · groupbase</title></svelte:head>

<div class="page-head">
	<h1>Предметы</h1>
	{#if target && can('manage_subjects', target.id)}
		<Button variant="primary" onclick={() => (editor = true)}><Plus size={17} /> Предмет</Button>
	{/if}
</div>

{#if requests.length}
	<section class="requests">
		{#each requests as r (r.id)}
			<div class="req card" in:fly>
				<div>
					<strong>{r.subjectName}</strong>
					<p class="muted small">{r.fromGroupName} → {r.toGroupName}: сделать предмет общим</p>
				</div>
				<span class="spacer"></span>
				{#if groups().some((g) => g.id === r.toGroupId && g.permissions.includes('share_subjects')) || session.me?.user.instanceRole === 'admin'}
					<Button size="s" variant="primary" onclick={() => decide(r, true)}>Принять</Button>
					<Button size="s" onclick={() => decide(r, false)}>Отклонить</Button>
				{:else}
					<span class="chip amber">Ждёт ответа</span>
					<Button size="s" variant="ghost" onclick={() => decide(r, false)}>Отозвать</Button>
				{/if}
			</div>
		{/each}
	</section>
{/if}

{#if visible.length === 0}
	<div class="card">
		<Empty
			title="Предметов пока нет"
			text="Староста добавит предметы, и сюда будут попадать задания и материалы."
		/>
	</div>
{:else}
	<div class="grid">
		{#each visible as s, i (s.id)}
			<a
				class="subject card"
				href="/subjects/{s.id}"
				in:fly={{ y: 10, delay: stagger(i) }}
				class:archived={s.archived}
			>
				<span class="cover">
					<SubjectArt id={s.id} name={s.name} color={s.color} avatar={s.avatar} class="fill" />
					{#if s.groups.length > 1 || s.archived}
						<span class="tags">
							{#if s.groups.length > 1}<span class="chip glass"
									><Users size={12} /> общий · {s.groups.length}</span
								>{/if}
							{#if s.archived}<span class="chip glass"><Archive size={12} /> архив</span>{/if}
						</span>
					{/if}
				</span>
				<span class="foot">
					<span class="text">
						<strong class="name">{s.name}</strong>
						<span class="muted small teacher">{s.teacher || 'Преподаватель не указан'}</span>
					</span>
					<span class="circle ink" aria-hidden="true"><ArrowRight size={18} /></span>
				</span>
			</a>
		{/each}
	</div>
{/if}

{#if archivedCount}
	<button class="toggle-arch" onclick={() => (showArchived = !showArchived)}>
		{showArchived ? 'Скрыть архив' : `Показать архив (${archivedCount})`}
	</button>
{/if}

<SubjectEditor bind:open={editor} groupId={target?.id ?? null} onsaved={() => loadSubjects()} />

<style>
	.requests {
		display: flex;
		flex-direction: column;
		gap: var(--s2);
		margin-bottom: var(--s5);
	}
	.req {
		display: flex;
		align-items: center;
		gap: var(--s2);
		flex-wrap: wrap;
	}
	/* Карточки как «Upcoming tours»: обложка, название, круглая чёрная стрелка */
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
		gap: var(--s4);
	}
	.subject {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 12px;
		padding: 8px 8px 12px;
		color: var(--text);
		transition:
			transform var(--dur) var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.subject:hover {
		text-decoration: none;
		transform: translateY(-2px);
		box-shadow: var(--shadow-2);
	}
	.cover {
		position: relative;
		height: 148px;
	}
	.cover :global(.fill) {
		position: absolute;
		inset: 0;
		border-radius: 18px;
	}
	.tags {
		position: absolute;
		top: 10px;
		left: 10px;
		display: flex;
		gap: 6px;
	}
	.chip.glass {
		background: rgb(255 255 255 / 0.78);
		color: #0d0d0f;
		backdrop-filter: blur(10px);
		-webkit-backdrop-filter: blur(10px);
	}
	.foot {
		display: flex;
		align-items: center;
		gap: var(--s3);
		padding: 0 6px 0 8px;
	}
	.text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.name {
		font-size: 17px;
		letter-spacing: -0.02em;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.teacher {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.archived {
		opacity: 0.6;
	}
	.toggle-arch {
		margin-top: var(--s4);
		border: 0;
		background: none;
		color: var(--text-2);
		font-weight: 550;
	}
</style>
