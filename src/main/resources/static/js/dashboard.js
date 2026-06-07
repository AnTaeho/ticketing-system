const COLORS = [
    '#ef4444', '#f97316', '#eab308', '#22c55e', '#3b82f6', '#8b5cf6'
];

const CHART_DEFAULTS = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
        legend: { display: false },
        tooltip: { callbacks: { label: ctx => ` ${ctx.parsed.y.toLocaleString()}` } }
    },
    scales: {
        x: { ticks: { color: '#94a3b8', font: { size: 11 } }, grid: { color: '#1e293b' } },
        y: { ticks: { color: '#94a3b8', font: { size: 11 } }, grid: { color: '#334155' } }
    }
};

function makeBarChart(id, label, data, unit) {
    const ctx = document.getElementById(id);
    if (!ctx || !data || data.length === 0) return;
    new Chart(ctx, {
        type: 'bar',
        data: {
            labels: chartData.labels,
            datasets: [{
                label,
                data,
                backgroundColor: COLORS.slice(0, data.length),
                borderRadius: 6,
            }]
        },
        options: {
            ...CHART_DEFAULTS,
            plugins: {
                ...CHART_DEFAULTS.plugins,
                tooltip: { callbacks: { label: ctx => ` ${ctx.parsed.y.toLocaleString()} ${unit}` } }
            }
        }
    });
}

if (chartData && chartData.labels && chartData.labels.length > 0) {
    makeBarChart('tpsChart', 'TPS', chartData.tps, 'req/s');
    makeBarChart('p99Chart', 'P99', chartData.p99, 'ms');
    makeBarChart('overBookingChart', '오버부킹', chartData.overBooking, '건');
    makeBarChart('errorRateChart', '에러율', chartData.errorRate, '%');
}

let cbChartsInitialized = false;

function initCbCharts() {
    if (cbChartsInitialized) return;
    cbChartsInitialized = true;

    if (!cbResults || cbResults.length === 0) return;

    const colors = { redis: '#3b82f6', fallback: '#f87171', tps: '#22c55e', error: '#f97316' };
    const gridColor = '#334155';
    const tickColor = '#94a3b8';

    const labels = cbResults.map(r => {
        const chaos = (r.chaosType && r.chaosType !== 'NONE') ? 'Redis장애' : '정상';
        return `${r.concurrentUsers}명 - ${chaos}`;
    });

    const redisPathCounts = cbResults.map(r => (r.successCount || 0) - (r.fallbackCount || 0));
    const fallbackCounts  = cbResults.map(r => r.fallbackCount || 0);

    const scaleOpts = {
        x: { ticks: { color: tickColor }, grid: { color: gridColor } },
        y: { ticks: { color: tickColor }, grid: { color: gridColor } }
    };

    new Chart(document.getElementById('cbDistributionChart'), {
        type: 'bar',
        data: {
            labels,
            datasets: [
                { label: 'Redis 경로', data: redisPathCounts, backgroundColor: colors.redis },
                { label: 'V2 폴백',    data: fallbackCounts,  backgroundColor: colors.fallback }
            ]
        },
        options: {
            responsive: true, maintainAspectRatio: false,
            scales: {
                x: { ...scaleOpts.x, stacked: true },
                y: { ...scaleOpts.y, stacked: true }
            },
            plugins: { legend: { labels: { color: tickColor } } }
        }
    });

    new Chart(document.getElementById('cbTpsChart'), {
        type: 'bar',
        data: { labels, datasets: [{ label: 'TPS', data: cbResults.map(r => r.tps), backgroundColor: colors.tps }] },
        options: { responsive: true, maintainAspectRatio: false, scales: scaleOpts, plugins: { legend: { labels: { color: tickColor } } } }
    });

    new Chart(document.getElementById('cbErrorChart'), {
        type: 'bar',
        data: { labels, datasets: [{ label: '에러율 %', data: cbResults.map(r => r.errorRate), backgroundColor: colors.error }] },
        options: { responsive: true, maintainAspectRatio: false, scales: scaleOpts, plugins: { legend: { labels: { color: tickColor } } } }
    });
}

function deleteResult(id) {
    if (!confirm(`결과 #${id}을 삭제하시겠습니까?`)) return;
    fetch(`/api/test-results/${id}`, { method: 'DELETE' })
        .then(r => { if (r.ok) location.reload(); else alert('삭제 실패'); })
        .catch(() => alert('삭제 실패'));
}
