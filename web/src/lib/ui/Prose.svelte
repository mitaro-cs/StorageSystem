<script lang="ts">
	/** Вывод HTML, который сервер отрендерил из Markdown и прогнал через OWASP-санитайзер. */
	let { html, class: cls = '' }: { html: string; class?: string } = $props();
</script>

<div class="prose {cls}">
	<!-- eslint-disable-next-line svelte/no-at-html-tags -- HTML санитизирован на сервере -->
	{@html html}
</div>
