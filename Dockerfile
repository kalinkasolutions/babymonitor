# ---- Stage 1: restore & publish ----
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS dotnet_build_env
WORKDIR /src

# Copy just the project files first so `restore` is cached unless a .csproj changes.
COPY backend/Babymonitor.Api/Babymonitor.Api.csproj backend/Babymonitor.Api/
COPY backend/Directory.Build.props backend/
RUN dotnet restore backend/Babymonitor.Api/Babymonitor.Api.csproj --disable-parallel

COPY . .
RUN dotnet publish backend/Babymonitor.Api/Babymonitor.Api.csproj -c Release -o /app/out --no-restore

# ---- Stage 2: runtime image ----
FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app

COPY --from=dotnet_build_env /app/out ./

ENV ASPNETCORE_URLS=http://+:5001
EXPOSE 5001

ENTRYPOINT ["dotnet", "Babymonitor.Api.dll"]
