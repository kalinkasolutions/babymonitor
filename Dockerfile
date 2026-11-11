# ---- Stage 0: the web app, served by the backend from wwwroot ----
FROM node:24-alpine AS web_build_env
WORKDIR /web

COPY web/package.json web/package-lock.json ./
RUN npm ci --ignore-scripts

COPY web/ ./
RUN npx ng build

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
COPY --from=web_build_env /web/dist/web/browser ./wwwroot

ENV ASPNETCORE_URLS=http://+:5001
EXPOSE 5001

ENTRYPOINT ["dotnet", "Babymonitor.Api.dll"]
